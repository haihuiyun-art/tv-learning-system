# 第三方声明

本项目的自有源代码由 AI 生成，但以下第三方内容不属于本项目自有代码，也不因本项目采用 MIT 许可证而改变其原有权利或许可证。

## NanoHTTPD 2.3.1

- 项目：<https://github.com/NanoHttpd/nanohttpd>
- Maven 坐标：`org.nanohttpd:nanohttpd:2.3.1`
- 用途：在电视端提供局域网家长网页和 API。
- 许可证：BSD 3-Clause 风格许可证，原文如下；来源为 NanoHTTPD 官方仓库的 `LICENSE.md`。

```text
Copyright (c) 2012-2013 by Paul S. Hawke, 2001,2005-2013 by Jarno Elonen, 2010 by Konstantinos Togias
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

* Neither the name of the NanoHttpd organization nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Gradle Wrapper

- 项目：<https://github.com/gradle/gradle>
- 文件：`gradle/wrapper/gradle-wrapper.jar` 及 Wrapper 脚本。
- 许可证：Apache License 2.0。
- Wrapper JAR 内已包含 `META-INF/LICENSE`；Gradle 分发包及构建工具仍遵循其各自许可证。

## Android 与构建依赖

Android SDK、Android Gradle Plugin、JDK 及其他构建工具均由各自项目提供。仓库没有因此获得或重新授予这些第三方组件的权利。

## 第三方应用名称与图标

`docs/ui-preview/` 和界面预览中可能出现第三方视频应用名称或图标。这些内容仅用于兼容性说明和界面演示，权利归原权利人，不属于 AI 原创，也不按本项目的 MIT 许可证重新授权。

本项目与云视听极光、云视听小电视、CIBN 酷喵、TV BRO 及其他第三方应用或设备厂商没有隶属、合作或背书关系。实际部署时应从设备系统读取应用图标和名称，不要求把第三方应用安装包包含进本仓库。
