#!/usr/bin/env python3
import argparse
import datetime
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

MIN_SDK = 28
PACKAGE_NAME = "com.tvlearning.system"
COMPONENT_PATTERN = re.compile(
    r"^\s*([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+)\s*$",
    re.MULTILINE,
)


class ProbeError(Exception):
    pass


class Adb:
    def __init__(self, executable):
        self.executable = executable
        self.serial = None

    def run(self, arguments, label, timeout=20):
        command = [self.executable]
        if self.serial is not None:
            command.extend(["-s", self.serial])
        command.extend(arguments)
        try:
            result = subprocess.run(
                command,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as error:
            raise ProbeError(label + "超时") from error
        except OSError as error:
            raise ProbeError(label + "无法启动 ADB") from error
        if result.returncode != 0:
            raise ProbeError(label + "失败（ADB 退出码 %d）" % result.returncode)
        return result.stdout.decode("utf-8", errors="replace")

    def shell(self, arguments, label):
        return self.run(["shell"] + arguments, label)


def parse_args():
    parser = argparse.ArgumentParser(
        description="电视学习系统只读设备检测：不安装、不授权、不修改设备。"
    )
    parser.add_argument("--adb", help="adb 可执行文件路径；默认使用项目 .tools 或 PATH")
    parser.add_argument("--serial", help="已连接设备的 ADB 序列；不会写入报告")
    parser.add_argument("--connect", help="用户明确提供的 IP:端口；不会扫描网络或写入报告")
    parser.add_argument("--output", help="项目内的新 JSON 报告路径；已存在则拒绝覆盖")
    return parser.parse_args()


def project_root():
    return Path(__file__).resolve().parent.parent


def output_path(value):
    if not value:
        return None
    path = Path(value)
    path = (project_root() / path).resolve() if not path.is_absolute() else path.resolve()
    root = project_root()
    if path == root or root not in path.parents:
        raise ProbeError("报告只能保存到项目目录内")
    if path.suffix.lower() != ".json":
        raise ProbeError("报告文件必须使用 .json 扩展名")
    if path.exists():
        raise ProbeError("报告文件已存在，请换一个文件名，避免覆盖历史记录")
    return path


def find_adb(value):
    if value:
        path = Path(value).expanduser()
        if path.is_file():
            return str(path.resolve())
        executable = shutil.which(value)
        if executable:
            return executable
        raise ProbeError("找不到指定的 adb；请检查 --adb 路径")
    bundled = project_root() / ".tools" / "android-sdk" / "platform-tools" / "adb.exe"
    if bundled.is_file():
        return str(bundled)
    executable = shutil.which("adb")
    if executable:
        return executable
    raise ProbeError("找不到 adb；请安装 Android platform-tools 或使用 --adb 指定路径")


def select_device(adb, requested_serial, connect_target):
    if connect_target:
        if requested_serial and requested_serial != connect_target:
            raise ProbeError("--connect 与 --serial 必须指向同一目标")
        result = adb.run(["connect", connect_target], "连接指定设备", timeout=30)
        if "connected" not in result.lower():
            raise ProbeError("无法连接用户指定的设备；请检查地址和无线 ADB")
    serial = requested_serial or connect_target
    if serial:
        state = adb.run(["-s", serial, "get-state"], "读取设备连接状态").strip()
        if state != "device":
            raise ProbeError("目标设备未处于已授权的在线状态")
        return serial
    devices = []
    unavailable = []
    for line in adb.run(["devices"], "列出已连接设备").splitlines()[1:]:
        fields = line.split()
        if len(fields) < 2:
            continue
        if fields[1] == "device":
            devices.append(fields[0])
        else:
            unavailable.append(fields[1])
    if len(devices) == 1 and not unavailable:
        return devices[0]
    if not devices:
        raise ProbeError("没有已授权的在线设备；请先连接 ADB，或使用 --connect 指定用户提供的地址")
    raise ProbeError("存在多台或未就绪设备，请用 --serial 明确选择目标")


def as_int(value):
    if value is None or not re.fullmatch(r"-?\d+", value.strip()):
        return None
    return int(value.strip())


def as_bool_property(value):
    if value == "1":
        return True
    if value == "0":
        return False
    return None


def parse_feature(features, name):
    if not features:
        return None
    return ("feature:" + name) in features.split()


def parse_service(output):
    if "Service device_policy: found" in output:
        return True
    if "not found" in output:
        return False
    return None


def parse_owner_present(output):
    if not output:
        return None
    if re.search(r"^\s*Device Owner\s*:", output, re.MULTILINE):
        return True
    if "Current Device Policy Manager state" in output or "DevicePolicyManagerService" in output:
        return False
    return None


def parse_users(output):
    if not output:
        return None
    return len(re.findall(r"\bUserInfo\{\d+:", output))


def parse_usage_mode(output):
    if not output:
        return None
    match = re.search(r"GET_USAGE_STATS:\s*([a-z]+)", output, re.IGNORECASE)
    return match.group(1).lower() if match else None


def parse_display_size(output):
    if not output:
        return None
    match = re.search(r"Physical size:\s*(\d+)x(\d+)", output)
    if not match:
        return None
    return {"width": int(match.group(1)), "height": int(match.group(2))}


def parse_display_density(output):
    if not output:
        return None
    match = re.search(r"Physical density:\s*(\d+)", output)
    return int(match.group(1)) if match else None


def build_report(adb):
    errors = []

    def query(label, arguments, shell=True):
        try:
            return adb.shell(arguments, label) if shell else adb.run(arguments, label)
        except ProbeError as error:
            errors.append({"query": label, "error": str(error)})
            return ""

    property_names = {
        "manufacturer": "ro.product.manufacturer",
        "model": "ro.product.model",
        "product": "ro.product.name",
        "device": "ro.product.device",
        "fingerprint": "ro.build.fingerprint",
        "android_release": "ro.build.version.release",
        "sdk": "ro.build.version.sdk",
        "abi": "ro.product.cpu.abi",
        "abi_list": "ro.product.cpu.abilist",
        "debuggable": "ro.debuggable",
    }
    properties = {}
    for field, name in property_names.items():
        value = query("系统属性 " + field, ["getprop", name]).strip()
        properties[field] = value or None

    sdk = as_int(properties["sdk"])
    features = query("系统特性", ["pm", "list", "features"])
    policy_service = parse_service(query("设备策略服务", ["service", "check", "device_policy"]))
    owner_present = parse_owner_present(query("现有设备所有者", ["dumpsys", "device_policy"]))
    users = parse_users(query("设备用户", ["pm", "list", "users"]))
    current_user = as_int(query("当前用户", ["am", "get-current-user"]).strip())
    boot_count = as_int(query("开机计数", ["settings", "get", "global", "boot_count"]).strip())
    if boot_count is not None and boot_count < 0:
        boot_count = None

    home_action = ["-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"]
    current_home_text = query("当前默认桌面", ["cmd", "package", "resolve-activity", "--brief"] + home_action)
    home_text = query("桌面候选", ["cmd", "package", "query-activities", "--brief"] + home_action)
    current_home_match = COMPONENT_PATTERN.search(current_home_text)
    current_home = current_home_match.group(1) if current_home_match else None
    home_candidates = list(dict.fromkeys(COMPONENT_PATTERN.findall(home_text)))
    original_homes = [
        component for component in home_candidates if not component.startswith(PACKAGE_NAME + "/")
    ]

    package_text = query("现有学习系统包", ["pm", "list", "packages", "--show-versioncode", PACKAGE_NAME])
    installed = bool(re.search(r"^package:" + re.escape(PACKAGE_NAME) + r"(?:\s|$)", package_text, re.MULTILINE))
    version_match = re.search(r"\bversionCode:(\d+)", package_text)
    usage_text = query("观看统计模式", ["appops", "get", PACKAGE_NAME, "android:get_usage_stats"])

    reasons = []
    if sdk is None:
        tier = "needs_review"
        basic_candidate = None
        reasons.append("无法读取 Android SDK，暂不安装")
    elif sdk < MIN_SDK:
        tier = "unsupported"
        basic_candidate = False
        reasons.append("当前应用要求 Android 9 / API 28 及以上")
    else:
        basic_candidate = True
        tier = "basic_candidate"
        reasons.append("达到当前 APK 的 API 门槛，但仍须完成真实视频和实体遥控器验收")
        if sdk != MIN_SDK:
            reasons.append("现有主要实测为 Android 9，不能把代码门槛当作此系统版本已兼容")
    if not home_candidates:
        if basic_candidate is not False:
            tier = "needs_review"
            basic_candidate = None
        reasons.append("未读取到桌面候选，需人工确认桌面切换和回退路径")
    elif not original_homes:
        if basic_candidate is not False:
            tier = "needs_review"
            basic_candidate = None
        reasons.append("未找到原系统桌面候选，暂不进行默认桌面变更")
    if errors:
        reasons.append("部分只读查询失败，相关字段保留未知，不得据此宣称兼容")

    owner_candidate = False
    if basic_candidate is not True:
        reasons.append("基础部署尚未满足候选条件，不进入 Device Owner 评估")
    elif parse_feature(features, "android.software.device_admin") is not True:
        reasons.append("ROM 未声明或无法确认 android.software.device_admin，不尝试激活 Device Owner")
    elif policy_service is not True:
        reasons.append("设备策略服务缺失或状态未知，不尝试激活 Device Owner")
    elif owner_present is not False:
        reasons.append("已有设备所有者或状态无法确认，不替换现有管理组件")
    elif users != 1 or current_user != 0:
        reasons.append("用户数量或当前用户不符合单用户机主前提，不尝试激活 Device Owner")
    else:
        owner_candidate = True
        tier = "device_owner_candidate"
        reasons.append("仅具备 Device Owner 进一步验证条件；账户和厂商限制仍可能拒绝，必须另行授权并实测")

    return {
        "schema_version": 1,
        "created_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "probe_only": True,
        "device": {
            "manufacturer": properties["manufacturer"],
            "model": properties["model"],
            "product": properties["product"],
            "device": properties["device"],
            "fingerprint": properties["fingerprint"],
            "abi": properties["abi"],
            "supported_abis": properties["abi_list"].split(",") if properties["abi_list"] else None,
        },
        "system": {
            "android_release": properties["android_release"],
            "sdk": sdk,
            "min_sdk_required": MIN_SDK,
            "debuggable": as_bool_property(properties["debuggable"]),
            "current_user": current_user,
            "user_count": users,
            "boot_count": boot_count,
            "device_admin_declared": parse_feature(features, "android.software.device_admin"),
            "leanback_declared": parse_feature(features, "android.software.leanback"),
            "device_policy_service": policy_service,
            "device_owner_present": owner_present,
        },
        "home": {
            "current": current_home,
            "current_is_learning_app": current_home.startswith(PACKAGE_NAME + "/") if current_home else None,
            "candidates": home_candidates,
            "original_candidates": original_homes,
        },
        "existing_app": {
            "package_name": PACKAGE_NAME,
            "installed": installed,
            "version_code": as_int(version_match.group(1)) if version_match else None,
            "usage_stats_mode": parse_usage_mode(usage_text),
        },
        "display": {
            "size": parse_display_size(query("屏幕尺寸", ["wm", "size"])),
            "density": parse_display_density(query("屏幕密度", ["wm", "density"])),
        },
        "assessment": {
            "tier": tier,
            "basic_candidate": basic_candidate,
            "device_owner_candidate": owner_candidate,
            "one_click_verified": False,
            "reasons": reasons,
            "required_checks": [
                "实际受管视频的前台事件与自然计时",
                "遥控器答题、Home/返回和厂商语音入口",
                "覆盖升级、正常重启及观看/锁定/冷却状态的真实拔电",
                "厂商休眠时停止计时，唤醒后恢复",
                "Device Owner 仅在具备条件且单独授权后验证，不删除账户或恢复出厂",
            ],
        },
        "query_errors": errors,
        "safety": {
            "network_connection_requested": bool(connect_requested(adb)),
            "device_mutations_attempted": False,
            "reboot_performed": False,
            "excluded_data": [
                "ADB/IP/硬件序列号",
                "账户名称、PIN、恢复码和网页会话",
                "题库及应用私有数据",
            ],
        },
    }


def connect_requested(adb):
    return getattr(adb, "connect_requested", False)


def main():
    args = parse_args()
    destination = output_path(args.output)
    adb = Adb(find_adb(args.adb))
    adb.connect_requested = bool(args.connect)
    adb.serial = select_device(adb, args.serial, args.connect)
    report = build_report(adb)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if destination:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
    sys.stdout.write(text)


if __name__ == "__main__":
    for output_stream in (sys.stdout, sys.stderr):
        if hasattr(output_stream, "reconfigure"):
            output_stream.reconfigure(encoding="utf-8")
    try:
        main()
    except ProbeError as error:
        print("检测失败：" + str(error), file=sys.stderr)
        sys.exit(2)
