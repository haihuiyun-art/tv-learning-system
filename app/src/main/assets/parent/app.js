/* 电视学习管控 · 家长端静态页面
 * 纯原生 JS，无外部依赖。所有动态文本均通过 textContent / DOM API 写入，不使用 innerHTML 渲染服务端或题库内容。
 */
(function () {
  'use strict';

  var POLL_INTERVAL_MS = 5000;
  var csrfToken = null;        // setup/login 成功后保存在内存中
  var pollTimer = null;
  var policyDirty = false;     // 用户正在编辑规则，自动刷新不覆盖输入
  var appsSignature = '';      // 应用列表变化指纹，避免无意义重绘
  var pendingImport = null;    // 粘贴区本地预览通过、待导入的题库
  var pendingFiles = [];       // 多文件逐个读取和上传状态
  var bankSummaries = [];      // 最近一次从设备获取的题库文件摘要

  var $ = function (id) { return document.getElementById(id); };

  /* ---------- 基础请求封装 ---------- */

  function readError(xhr) {
    var msg = '请求失败（HTTP ' + xhr.status + '）';
    try {
      var body = JSON.parse(xhr.responseText);
      if (body && typeof body.message === 'string' && body.message) return body.message;
      if (body && typeof body.error === 'string' && body.error) return body.error;
    } catch (e) { /* 忽略非 JSON 响应 */ }
    return msg;
  }

  // method, path, body(可空), opts { skipCsrf:boolean }
  function api(method, path, body, opts) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open(method, path, true);
      xhr.withCredentials = true; // 同源下携带 HttpOnly 会话 Cookie
      xhr.setRequestHeader('Accept', 'application/json');
      if (body !== null && body !== undefined) {
        xhr.setRequestHeader('Content-Type', 'application/json; charset=UTF-8');
      }
      if (method !== 'GET' && !(opts && opts.skipCsrf) && csrfToken) {
        xhr.setRequestHeader('X-CSRF-Token', csrfToken);
      }
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) return;
        var data = null;
        if (xhr.responseText) {
          try { data = JSON.parse(xhr.responseText); } catch (e) { data = null; }
        }
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(data);
        } else {
          var err = new Error(readError(xhr));
          err.status = xhr.status;
          reject(err);
        }
      };
      xhr.onerror = function () {
        reject(new Error('网络连接失败，请确认手机与电视在同一局域网'));
      };
      xhr.send(body !== null && body !== undefined ? JSON.stringify(body) : null);
    });
  }

  /* ---------- 工具 ---------- */

  function setMsg(el, text, isError) {
    el.textContent = text || '';
    el.classList.remove('ok', 'err');
    if (text) el.classList.add(isError ? 'err' : 'ok');
  }

  function fmtSeconds(sec) {
    if (typeof sec !== 'number' || isNaN(sec)) return '—';
    if (sec <= 0) return '0 分钟';
    var m = Math.floor(sec / 60);
    var s = Math.round(sec % 60);
    if (m <= 0) return s + ' 秒';
    return m + ' 分 ' + s + ' 秒';
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function clearChildren(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function showView(name) {
    ['view-setup', 'view-login', 'view-main'].forEach(function (id) {
      $(id).classList.toggle('hidden', id !== 'view-' + name);
    });
    $('loading').classList.add('hidden');
  }

  /* ---------- 启动流程 ---------- */

  function boot() {
    api('GET', '/api/bootstrap').then(function (b) {
      if (b && b.configured === false) {
        showView('setup');
        return;
      }
      if (b && typeof b.deviceName === 'string') {
        $('login-device-name').textContent = b.deviceName;
      }
      // 已配置：尝试直接拉取状态，Cookie 仍有效则进入主界面，否则要求登录
      return refreshState().then(function () {
        enterMain();
      }).catch(function (err) {
        if (err && err.status === 401) {
          showView('login');
        } else {
          showView('login');
          setMsg($('login-error'), err.message || '连接失败', true);
        }
      });
    }).catch(function (err) {
      $('loading').textContent = err.message || '无法连接设备';
    });
  }

  function enterMain() {
    showView('main');
    refreshQuestionBanks().catch(function (err) {
      setMsg($('q-banks-msg'), '加载题库文件失败：' + err.message, true);
    });
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = setInterval(function () {
      refreshState().catch(function (err) {
        if (err && err.status === 401) {
          clearInterval(pollTimer);
          pollTimer = null;
          csrfToken = null;
          showView('login');
          setMsg($('login-error'), '会话已过期，请重新登录', true);
        }
      });
    }, POLL_INTERVAL_MS);
  }

  /* ---------- 设置与登录 ---------- */

  $('form-setup').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var pairing = $('setup-pairing').value.trim();
    var pin = $('setup-pin').value;
    var pin2 = $('setup-pin2').value;
    var errEl = $('setup-error');
    setMsg(errEl, '');
    if (!pairing) { setMsg(errEl, '请输入电视屏幕上显示的配对码', true); return; }
    if (!/^\d{6,8}$/.test(pin)) { setMsg(errEl, 'PIN 需为 6~8 位数字', true); return; }
    if (pin !== pin2) { setMsg(errEl, '两次输入的 PIN 不一致', true); return; }
    api('POST', '/api/setup', { pairingCode: pairing, pin: pin }, { skipCsrf: true })
      .then(function (res) {
        if (res && typeof res.csrfToken === 'string') csrfToken = res.csrfToken;
        return refreshState().then(function () { enterMain(); });
      })
      .catch(function (err) {
        setMsg(errEl, '设置失败：' + err.message, true);
      });
  });

  $('form-login').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var secret = $('login-pin').value.trim();
    var errEl = $('login-error');
    setMsg(errEl, '');
    if (!/^\d{6,8}$/.test(secret) && !/^TV-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/i.test(secret)) {
      setMsg(errEl, '请输入 6~8 位数字 PIN 或 TV 恢复码', true);
      return;
    }
    api('POST', '/api/login', { pin: secret }, { skipCsrf: true })
      .then(function (res) {
        if (res && typeof res.csrfToken === 'string') csrfToken = res.csrfToken;
        $('login-pin').value = '';
        return refreshState().then(function () { enterMain(); });
      })
      .catch(function (err) {
        setMsg(errEl, '登录失败：' + err.message, true);
      });
  });

  $('form-change-pin').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var current = $('change-current').value.trim();
    var next = $('change-new').value;
    var next2 = $('change-new2').value;
    var errEl = $('change-pin-msg');
    setMsg(errEl, '');
    if (!current) { setMsg(errEl, '请输入当前 PIN 或恢复码', true); return; }
    if (!/^\d{6,8}$/.test(next)) { setMsg(errEl, '新 PIN 需为 6~8 位数字', true); return; }
    if (next !== next2) { setMsg(errEl, '两次输入的新 PIN 不一致', true); return; }
    var btn = $('btn-change-pin');
    btn.disabled = true;
    api('POST', '/api/security/change-pin', { currentSecret: current, newPin: next })
      .then(function () {
        $('form-change-pin').reset();
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
        csrfToken = null;
        showView('login');
        setMsg($('login-error'), 'PIN 和恢复码已更新，请用新 PIN 登录。新恢复码需在电视端重新验证后查看。', false);
      })
      .catch(function (err) {
        setMsg(errEl, '修改失败：' + err.message, true);
      })
      .then(function () {
        btn.disabled = false;
      });
  });

  $('btn-logout').addEventListener('click', function () {
    api('POST', '/api/logout', {}).catch(function () { /* 即使失败也回到登录页 */ })
      .then(function () {
        csrfToken = null;
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
        showView('login');
        setMsg($('login-error'), '已退出登录', false);
      });
  });

  /* ---------- 标签页 ---------- */

  $('tabs').addEventListener('click', function (ev) {
    var btn = ev.target;
    if (!btn || !btn.getAttribute('data-tab')) return;
    var name = btn.getAttribute('data-tab');
    var tabs = $('tabs').querySelectorAll('.tab');
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].classList.toggle('active', tabs[i] === btn);
    }
    var panels = document.querySelectorAll('.panel');
    for (var j = 0; j < panels.length; j++) {
      panels[j].classList.toggle('active', panels[j].id === 'panel-' + name);
    }
    if (name === 'questions') {
      refreshQuestionBanks().catch(function (err) {
        setMsg($('q-banks-msg'), '加载题库文件失败：' + err.message, true);
      });
    }
  });

  /* ---------- 状态刷新与渲染 ---------- */

  function refreshState() {
    return api('GET', '/api/state').then(function (s) {
      if (!s) return;
      if (typeof s.csrfToken === 'string') csrfToken = s.csrfToken;
      renderState(s);
    });
  }

  function renderState(s) {
    if (typeof s.deviceName === 'string') $('main-device-name').textContent = s.deviceName;
    $('st-state').textContent = stateText(s.state);
    $('st-protection').textContent = s.protectionEnabled ? '已开启' : '已暂停';
    $('st-remaining').textContent = fmtSeconds(s.remainingWatchSeconds);
    $('st-cooldown').textContent = (typeof s.cooldownRemainingSeconds === 'number' && s.cooldownRemainingSeconds > 0)
      ? fmtSeconds(s.cooldownRemainingSeconds) : '无';
    $('st-daily').textContent = (typeof s.dailyUsedMinutes === 'number') ? s.dailyUsedMinutes + ' 分钟' : '—';
    if (s.questions) {
      $('st-questions').textContent = (s.questions.enabledQuestionCount || 0) + ' 题，'
        + (s.questions.enabledFileCount || 0) + '/' + (s.questions.fileCount || 0) + ' 个文件有效启用';
      $('q-file-count').textContent = String(s.questions.fileCount || 0);
      $('q-enabled-file-count').textContent = String(s.questions.enabledFileCount || 0);
      $('q-count').textContent = String(s.questions.enabledQuestionCount || 0);
      $('q-total-count').textContent = String(s.questions.totalQuestionCount || 0);
    }
    $('st-address').textContent = s.address || '—';
    renderHealth(s.health);

    if (s.policy && !policyDirty) fillPolicyForm(s.policy);
    if (Array.isArray(s.apps)) renderApps(s.apps);
    if (Array.isArray(s.logs)) renderLogs(s.logs);
  }

  function renderHealth(h) {
    if (!h || typeof h !== 'object') return;
    $('st-health-summary').textContent = h.summary || '无法取得健康状态';
    var box = $('st-health');
    box.classList.remove('health-ok', 'health-warning', 'health-error', 'health-setup', 'health-paused');
    var levels = ['ok', 'warning', 'error', 'setup', 'paused'];
    if (levels.indexOf(h.level) >= 0) box.classList.add('health-' + h.level);
    var issues = $('st-health-issues');
    clearChildren(issues);
    if (Array.isArray(h.issues)) {
      h.issues.forEach(function (issue) {
        if (typeof issue === 'string' && issue) issues.appendChild(el('li', null, issue));
      });
    }
    $('st-control-mode').textContent = h.controlMode || '—';
    $('st-start-reason').textContent = h.startReasonText || '—';
    $('st-uptime').textContent = fmtSeconds(h.serviceUptimeSeconds);
  }

  function stateText(state) {
    var map = {
      watching: '观看中',
      locked: '待答题解锁',
      cooldown: '答错冷却中',
      break: '强制休息中',
      quiet: '晚间禁用中',
      daily_limit: '已达到每日上限',
      idle: '空闲'
    };
    if (typeof state !== 'string' || !state) return '—';
    return map[state] || state;
  }

  /* ---------- 时间规则 ---------- */

  var POLICY_FIELDS = [
    ['initialWatchMinutes', 'p-initial'],
    ['rewardMinutes', 'p-reward'],
    ['retryDelayMinutes', 'p-retry'],
    ['dailyLimitMinutes', 'p-daily'],
    ['breakIntervalMinutes', 'p-break-interval'],
    ['breakDurationMinutes', 'p-break-duration'],
    ['quietStart', 'p-quiet-start'],
    ['quietEnd', 'p-quiet-end']
  ];

  function fillPolicyForm(p) {
    POLICY_FIELDS.forEach(function (pair) {
      var v = p[pair[0]];
      if (v !== undefined && v !== null) $(pair[1]).value = String(v);
    });
  }

  $('form-policy').addEventListener('input', function () { policyDirty = true; });

  $('form-policy').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var msgEl = $('policy-msg');
    setMsg(msgEl, '');
    var policy = {};
    for (var i = 0; i < POLICY_FIELDS.length; i++) {
      var key = POLICY_FIELDS[i][0];
      var input = $(POLICY_FIELDS[i][1]);
      if (input.type === 'number') {
        var n = parseInt(input.value, 10);
        if (isNaN(n)) { setMsg(msgEl, '请完整填写所有数字项', true); return; }
        var min = parseInt(input.min, 10);
        var max = parseInt(input.max, 10);
        if (!isNaN(min) && n < min) { setMsg(msgEl, '有数值小于允许范围，请检查', true); input.focus(); return; }
        if (!isNaN(max) && n > max) { setMsg(msgEl, '有数值超出允许范围，请检查', true); input.focus(); return; }
        policy[key] = n;
      } else {
        if (!/^\d{2}:\d{2}$/.test(input.value)) { setMsg(msgEl, '请填写安静时段的开始与结束时间', true); return; }
        policy[key] = input.value;
      }
    }
    api('POST', '/api/policy', policy)
      .then(function () {
        policyDirty = false;
        setMsg(msgEl, '保存成功，规则已生效', false);
      })
      .catch(function (err) {
        setMsg(msgEl, '保存失败：' + err.message, true);
      });
  });

  /* ---------- 受管应用 ---------- */

  function renderApps(apps) {
    var sig = apps.map(function (a) {
      return a.packageName + ':' + (a.enabled ? '1' : '0') + ':' + a.label;
    }).join('|');
    if (sig === appsSignature) return;
    appsSignature = sig;

    var list = $('app-list');
    clearChildren(list);
    $('apps-empty').classList.toggle('hidden', apps.length > 0);
    apps.forEach(function (app) {
      var li = el('li');
      var cb = el('input');
      cb.type = 'checkbox';
      cb.checked = !!app.enabled;
      cb.setAttribute('aria-label', '管控 ' + (app.label || app.packageName));
      cb.addEventListener('change', function () {
        setAppEnabled(app.packageName, cb.checked, cb);
      });
      var info = el('div', 'app-info');
      info.appendChild(el('div', 'app-label', app.label || app.packageName));
      info.appendChild(el('div', 'app-pkg', app.packageName));
      li.appendChild(cb);
      li.appendChild(info);
      list.appendChild(li);
    });
  }

  function setAppEnabled(packageName, enabled, checkbox) {
    var msgEl = $('apps-msg');
    setMsg(msgEl, '');
    checkbox.disabled = true;
    api('POST', '/api/control', { action: 'set_app_enabled', packageName: packageName, enabled: enabled })
      .then(function () {
        setMsg(msgEl, '已保存', false);
        return refreshState();
      })
      .catch(function (err) {
        checkbox.checked = !enabled; // 回滚勾选
        setMsg(msgEl, '保存失败：' + err.message, true);
      })
      .then(function () {
        checkbox.disabled = false;
      });
  }

  /* ---------- 控制操作 ---------- */

  var ACTION_NAMES = {
    unlock: '直接解锁',
    skip_question: '跳过当前题目',
    grant_without_answer: '免答奖励',
    open_original_home: '打开原系统桌面',
    pause_protection: '暂停保护',
    resume_protection: '恢复保护'
  };

  document.querySelectorAll('#panel-control [data-action]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var action = btn.getAttribute('data-action');
      var msgEl = $('control-msg');
      setMsg(msgEl, '');
      var body = { action: action };
      if (action === 'unlock') {
        var minutes = parseInt($('unlock-minutes').value, 10);
        if (isNaN(minutes) || minutes < 1 || minutes > 180) {
          setMsg(msgEl, '解锁分钟数需在 1~180 之间', true);
          return;
        }
        body.minutes = minutes;
      }
      btn.disabled = true;
      api('POST', '/api/control', body)
        .then(function () {
          var message = action === 'open_original_home'
            ? '已请求打开原桌面；返回学习主页后恢复管控'
            : (ACTION_NAMES[action] || action) + '：操作成功';
          setMsg(msgEl, message, false);
          return refreshState();
        })
        .catch(function (err) {
          setMsg(msgEl, (ACTION_NAMES[action] || action) + '：操作失败 — ' + err.message, true);
        })
        .then(function () {
          btn.disabled = false;
        });
    });
  });

  /* ---------- 题库 ---------- */

  var DIFFICULTIES = ['简单', '中等', '困难'];
  var MAX_BANK_BYTES = 4 * 1024 * 1024;
  var fileUploadRunning = false;

  function requiredStringError(value, field, maxLength, pos) {
    if (typeof value !== 'string' || !value.trim()) return pos + field + ' 必须是非空字符串';
    if (value.trim().length > maxLength) return pos + field + ' 不能超过 ' + maxLength + ' 个字符';
    return null;
  }

  function validateQuestionBank(raw) {
    var error;
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return '顶层必须是 JSON 对象';
    if (raw.formatVersion !== 1) return 'formatVersion 必须为数字 1';
    error = requiredStringError(raw.version, 'version', 80, '');
    if (error) return error;
    if (!Array.isArray(raw.questions) || raw.questions.length === 0) return 'questions 必须是非空数组';
    if (raw.questions.length > 500) return '每份题库最多 500 题';
    var seenIds = Object.create(null);
    var seenStems = Object.create(null);
    for (var i = 0; i < raw.questions.length; i++) {
      var q = raw.questions[i];
      var pos = '第 ' + (i + 1) + ' 题：';
      if (!q || typeof q !== 'object' || Array.isArray(q)) return pos + '必须是对象';
      error = requiredStringError(q.id, 'id', 64, pos);
      if (error) return error;
      var id = q.id.trim();
      if (!/^[A-Za-z0-9_-]+$/.test(id)) return pos + 'id 只能包含英文字母、数字、下划线和短横线';
      if (seenIds[id]) return pos + 'id "' + id + '" 重复';
      seenIds[id] = true;
      error = requiredStringError(q.grade, 'grade', 30, pos);
      if (error) return error;
      error = requiredStringError(q.subject, 'subject', 30, pos);
      if (error) return error;
      error = requiredStringError(q.topic, 'topic', 80, pos);
      if (error) return error;
      error = requiredStringError(q.difficulty, 'difficulty', 10, pos);
      if (error) return error;
      if (DIFFICULTIES.indexOf(q.difficulty.trim()) < 0) {
        return pos + 'difficulty 必须是「简单 / 中等 / 困难」之一';
      }
      error = requiredStringError(q.stem, 'stem', 2000, pos);
      if (error) return error;
      var stem = q.stem.trim();
      if (seenStems[stem]) return pos + '题干与前面的题目重复';
      seenStems[stem] = true;
      if (!Array.isArray(q.options) || q.options.length < 2 || q.options.length > 6) {
        return pos + 'options 必须是 2~6 个选项的数组';
      }
      for (var j = 0; j < q.options.length; j++) {
        error = requiredStringError(q.options[j], '第 ' + (j + 1) + ' 个选项', 300, pos);
        if (error) return error;
      }
      if (typeof q.answer !== 'number' || !isFinite(q.answer) || Math.floor(q.answer) !== q.answer ||
          q.answer < 0 || q.answer >= q.options.length) {
        return pos + 'answer 必须是 0 到 ' + (q.options.length - 1) + ' 之间的整数（从 0 开始的正确选项下标）';
      }
      error = requiredStringError(q.explanation, 'explanation', 3000, pos);
      if (error) return error;
      error = requiredStringError(q.source, 'source', 200, pos);
      if (error) return error;
    }
    return null;
  }

  function utf8Size(text) {
    return new Blob([text]).size;
  }

  function validBankName(name) {
    return typeof name === 'string' && name.trim().length > 0 && name.trim().length <= 80;
  }

  function importPayload(item) {
    return {
      formatVersion: 1,
      version: item.raw.version,
      questions: item.raw.questions,
      name: item.name.trim()
    };
  }

  function renderQuestionItem(q, index) {
    var li = el('li');
    li.appendChild(el('div', 'q-meta',
      '#' + (index + 1) + '　' + q.grade + ' · ' + q.subject +
      (q.topic ? ' · ' + q.topic : '') + ' · ' + q.difficulty + ' · id: ' + q.id));
    li.appendChild(el('div', 'q-stem', q.stem));
    var ol = el('ol', 'q-options');
    ol.type = 'A';
    q.options.forEach(function (opt, i) {
      var item = el('li', i === q.answer ? 'q-correct' : null, opt + (i === q.answer ? ' ✓ 正确答案' : ''));
      ol.appendChild(item);
    });
    li.appendChild(ol);
    li.appendChild(el('div', 'q-exp', '解析：' + q.explanation));
    return li;
  }

  function renderQuestions(list, questions) {
    clearChildren(list);
    questions.forEach(function (q, i) {
      list.appendChild(renderQuestionItem(q, i));
    });
    if (questions.length === 0) list.appendChild(el('li', null, '没有可显示的有效题目'));
  }

  function findBankSummary(name) {
    for (var i = 0; i < bankSummaries.length; i++) {
      if (bankSummaries[i].name === name.trim()) return bankSummaries[i];
    }
    return null;
  }

  function confirmSameNameUpdate(name) {
    var existing = findBankSummary(name);
    if (!existing) return true;
    return window.confirm('已存在同名题库“' + name.trim() + '”。确认更新这一份文件吗？\n'
      + '其他题库不会改变，原启用状态将保留。');
  }

  function rememberBank(bank) {
    if (!bank) return;
    for (var i = 0; i < bankSummaries.length; i++) {
      if (bankSummaries[i].bankId === bank.bankId) {
        bankSummaries[i] = bank;
        return;
      }
    }
    bankSummaries.push(bank);
  }

  function refreshQuestionBanks() {
    return api('GET', '/api/questions').then(function (data) {
      bankSummaries = data && Array.isArray(data.banks) ? data.banks : [];
      renderBankList();
      if (data) renderQuestionSummary(data);
      return data;
    });
  }

  function renderQuestionSummary(data) {
    $('q-file-count').textContent = String(data.fileCount || 0);
    $('q-enabled-file-count').textContent = String(data.enabledFileCount || 0);
    $('q-count').textContent = String(data.enabledQuestionCount || 0);
    $('q-total-count').textContent = String(data.totalQuestionCount || 0);
  }

  function renderBankList() {
    var list = $('bank-list');
    clearChildren(list);
    $('banks-empty').classList.toggle('hidden', bankSummaries.length > 0);
    bankSummaries.forEach(function (bank) {
      var li = el('li', 'bank-item');
      var heading = el('div', 'bank-heading');
      heading.appendChild(el('div', 'bank-name', bank.name || '未命名题库'));
      var effective = bank.enabled && bank.valid;
      heading.appendChild(el('span', effective ? 'badge badge-ok' : 'badge', effective ? '参与抽题' : (bank.enabled ? '已启用' : '已停用')));
      li.appendChild(heading);
      li.appendChild(el('div', 'bank-meta', '版本：' + (bank.version || '—') + ' · ' + (bank.questionCount || 0) + ' 题'));
      if (bank.error) li.appendChild(el('p', 'error', '错误：' + bank.error + '（此文件已隔离，不影响其他题库）'));
      var actions = el('div', 'bank-actions');
      var view = el('button', 'btn btn-small', '查看题目');
      view.type = 'button';
      view.addEventListener('click', function () { viewBank(bank, view); });
      var toggle = el('button', 'btn btn-small', bank.enabled ? '停用' : '启用');
      toggle.type = 'button';
      toggle.addEventListener('click', function () { setBankEnabled(bank, !bank.enabled, toggle); });
      var remove = el('button', 'btn btn-small btn-warn', '删除');
      remove.type = 'button';
      remove.addEventListener('click', function () { deleteBank(bank, remove); });
      actions.appendChild(view);
      actions.appendChild(toggle);
      actions.appendChild(remove);
      li.appendChild(actions);
      list.appendChild(li);
    });
  }

  function viewBank(bank, btn) {
    var msgEl = $('q-banks-msg');
    setMsg(msgEl, '');
    btn.disabled = true;
    $('q-list-wrap').classList.remove('hidden');
    $('bank-detail-title').textContent = bank.name + '：加载中…';
    api('GET', '/api/questions/bank?bankId=' + encodeURIComponent(bank.bankId))
      .then(function (data) {
        $('bank-detail-title').textContent = data.name || bank.name;
        $('bank-detail-meta').textContent = '版本：' + (data.version || '—') + ' · '
          + (data.questionCount || 0) + ' 题 · ' + (data.enabled ? '已启用' : '已停用')
          + (data.error ? ' · 错误：' + data.error : '');
        renderQuestions($('q-list'), Array.isArray(data.questions) ? data.questions : []);
      })
      .catch(function (err) {
        $('q-list-wrap').classList.add('hidden');
        setMsg(msgEl, '查看题库失败：' + err.message, true);
      })
      .then(function () { btn.disabled = false; });
  }

  function setBankEnabled(bank, enabled, btn) {
    var msgEl = $('q-banks-msg');
    setMsg(msgEl, '');
    btn.disabled = true;
    api('POST', '/api/questions/enable', { bankId: bank.bankId, enabled: enabled })
      .then(function () {
        setMsg(msgEl, (enabled ? '已启用：' : '已停用：') + bank.name, false);
        return Promise.all([refreshState(), refreshQuestionBanks()]);
      })
      .catch(function (err) { setMsg(msgEl, '保存启用状态失败：' + err.message, true); })
      .then(function () { btn.disabled = false; });
  }

  function deleteBank(bank, btn) {
    if (!window.confirm('确认删除题库“' + bank.name + '”及其全部题目吗？此操作不会影响其他文件。')) return;
    var msgEl = $('q-banks-msg');
    setMsg(msgEl, '');
    btn.disabled = true;
    api('POST', '/api/questions/delete', { bankId: bank.bankId })
      .then(function () {
        $('q-list-wrap').classList.add('hidden');
        setMsg(msgEl, '已删除：' + bank.name, false);
        return Promise.all([refreshState(), refreshQuestionBanks()]);
      })
      .catch(function (err) { setMsg(msgEl, '删除失败：' + err.message, true); })
      .then(function () { btn.disabled = false; });
  }

  $('btn-refresh-banks').addEventListener('click', function () {
    var btn = $('btn-refresh-banks');
    btn.disabled = true;
    refreshQuestionBanks()
      .then(function () { setMsg($('q-banks-msg'), '文件列表已刷新', false); })
      .catch(function (err) { setMsg($('q-banks-msg'), '刷新失败：' + err.message, true); })
      .then(function () { btn.disabled = false; });
  });

  $('btn-preview').addEventListener('click', function () {
    var msgEl = $('import-msg');
    setMsg(msgEl, '');
    $('preview-wrap').classList.add('hidden');
    pendingImport = null;
    var text = $('import-text').value.trim();
    if (!text) { setMsg(msgEl, '请先粘贴题库 JSON', true); return; }
    if (utf8Size(text) > MAX_BANK_BYTES) { setMsg(msgEl, '题库 JSON 超过 4 MB', true); return; }
    var name = $('import-name').value.trim();
    if (!validBankName(name) && name) { setMsg(msgEl, '题库名称不能超过 80 个字符', true); return; }
    var raw;
    try {
      raw = JSON.parse(text);
    } catch (e) {
      setMsg(msgEl, 'JSON 解析失败：' + e.message, true);
      return;
    }
    var err = validateQuestionBank(raw);
    if (err) { setMsg(msgEl, '格式检查未通过 — ' + err, true); return; }
    if (!name) name = raw.version.trim();
    if (!validBankName(name)) { setMsg(msgEl, '题库名称为空或超过 80 个字符', true); return; }
    var item = { name: name, raw: raw };
    if (utf8Size(JSON.stringify(importPayload(item))) > MAX_BANK_BYTES) {
      setMsg(msgEl, '添加名称后的上传 JSON 超过 4 MB，请缩短内容或名称', true);
      return;
    }

    renderQuestions($('preview-list'), raw.questions);
    $('preview-count').textContent = String(raw.questions.length);
    $('preview-wrap').classList.remove('hidden');
    pendingImport = item;
    setMsg(msgEl, '格式检查通过，请逐题核对答案与解析；上传只会添加或更新“' + name + '”', false);
  });

  $('btn-import').addEventListener('click', function () {
    var msgEl = $('import-msg');
    if (!pendingImport) { setMsg(msgEl, '请先通过本地预览检查', true); return; }
    var item = pendingImport;
    if (!confirmSameNameUpdate(item.name)) return;
    var replacing = !!findBankSummary(item.name);
    var btn = $('btn-import');
    btn.disabled = true;
    api('POST', '/api/questions/import', importPayload(item))
      .then(function (res) {
        rememberBank(res && res.bank);
        setMsg(msgEl, (replacing ? '同名题库更新成功：' : '题库添加成功：') + item.name
          + '（' + item.raw.questions.length + ' 题），其他文件未改变', false);
        $('import-text').value = '';
        $('import-name').value = '';
        $('preview-wrap').classList.add('hidden');
        pendingImport = null;
        return Promise.all([refreshState(), refreshQuestionBanks()]);
      })
      .catch(function (err) { setMsg(msgEl, '导入失败：' + err.message, true); })
      .then(function () { btn.disabled = false; });
  });

  function readFileText(file) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(String(reader.result || '')); };
      reader.onerror = function () { reject(new Error('无法读取文件')); };
      reader.readAsText(file, 'UTF-8');
    });
  }

  function fileBankName(fileName) {
    return fileName.replace(/\.json$/i, '').trim();
  }

  function readPendingFile(entry) {
    entry.status = '读取中…';
    entry.error = '';
    renderPendingFiles();
    if (!/\.json$/i.test(entry.file.name)) {
      entry.error = '只支持 .json 文件';
      renderPendingFiles();
      return Promise.resolve();
    }
    if (!validBankName(entry.name)) {
      entry.error = '去除 .json 后的文件名应为 1~80 个字符';
      renderPendingFiles();
      return Promise.resolve();
    }
    if (entry.file.size > MAX_BANK_BYTES) {
      entry.error = '文件超过 4 MB';
      renderPendingFiles();
      return Promise.resolve();
    }
    return readFileText(entry.file).then(function (text) {
      if (utf8Size(text) > MAX_BANK_BYTES) throw new Error('文件超过 4 MB');
      var raw;
      try {
        raw = JSON.parse(text);
      } catch (e) {
        throw new Error('JSON 解析失败：' + e.message);
      }
      var error = validateQuestionBank(raw);
      if (error) throw new Error(error);
      entry.raw = raw;
      if (utf8Size(JSON.stringify(importPayload(entry))) > MAX_BANK_BYTES) {
        entry.raw = null;
        throw new Error('添加名称后的上传 JSON 超过 4 MB，请缩短内容或文件名');
      }
      entry.status = '检查通过';
    }).catch(function (err) {
      entry.error = err.message || '文件检查失败';
    }).then(function () { renderPendingFiles(); });
  }

  function renderPendingFiles() {
    var wrap = $('file-preview-wrap');
    wrap.classList.toggle('hidden', pendingFiles.length === 0);
    var list = $('file-import-list');
    clearChildren(list);
    var uploadable = 0;
    pendingFiles.forEach(function (entry) {
      if (entry.raw && entry.previewed && !entry.uploaded) uploadable++;
      var li = el('li', 'file-import-item');
      var heading = el('div', 'bank-heading');
      heading.appendChild(el('div', 'bank-name', entry.file.name));
      var status = entry.uploading ? '上传中…' : (entry.uploaded ? '上传成功' : (entry.error ? '未通过/未上传' : entry.status));
      heading.appendChild(el('span', entry.uploaded ? 'badge badge-ok' : (entry.error ? 'badge badge-error' : 'badge'), status));
      li.appendChild(heading);
      var meta = entry.raw ? '名称：' + entry.name + ' · 版本：' + entry.raw.version + ' · ' + entry.raw.questions.length + ' 题' : '名称：' + entry.name;
      li.appendChild(el('div', 'bank-meta', meta));
      if (entry.error) li.appendChild(el('p', 'error', entry.error));
      if (entry.raw) {
        var preview = el('button', 'btn btn-small', '预览这份题目');
        preview.type = 'button';
        preview.addEventListener('click', function () { showPendingFilePreview(entry); });
        li.appendChild(preview);
      }
      list.appendChild(li);
    });
    $('btn-upload-files').disabled = fileUploadRunning || uploadable === 0;
  }

  function showPendingFilePreview(entry) {
    entry.previewed = true;
    $('file-preview-title').textContent = entry.file.name + '（' + entry.raw.questions.length + ' 题）';
    renderQuestions($('file-preview-list'), entry.raw.questions);
    $('file-preview-detail').classList.remove('hidden');
    renderPendingFiles();
  }

  $('btn-check-files').addEventListener('click', function () {
    var input = $('import-files');
    var msgEl = $('file-import-msg');
    setMsg(msgEl, '');
    $('file-preview-detail').classList.add('hidden');
    pendingFiles = [];
    if (!input.files || input.files.length === 0) {
      setMsg(msgEl, '请先选择一个或多个 .json 文件', true);
      renderPendingFiles();
      return;
    }
    var sequence = Promise.resolve();
    Array.prototype.forEach.call(input.files, function (file) {
      var entry = { file: file, name: fileBankName(file.name), raw: null, error: '', status: '等待读取', uploaded: false, uploading: false };
      pendingFiles.push(entry);
      sequence = sequence.then(function () { return readPendingFile(entry); });
    });
    renderPendingFiles();
    $('btn-check-files').disabled = true;
    sequence.then(function () {
      var valid = pendingFiles.filter(function (entry) { return !!entry.raw; }).length;
      setMsg(msgEl, '已逐个检查 ' + pendingFiles.length + ' 份文件：' + valid + ' 份通过，'
        + (pendingFiles.length - valid) + ' 份失败。请预览通过后逐份上传。', valid === 0);
    }).then(function () { $('btn-check-files').disabled = false; });
  });

  function uploadPendingFiles(entries, index) {
    if (index >= entries.length) return Promise.resolve();
    var entry = entries[index];
    if (!confirmSameNameUpdate(entry.name)) {
      entry.error = '已取消本次上传，可检查后重试';
      renderPendingFiles();
      return uploadPendingFiles(entries, index + 1);
    }
    entry.uploading = true;
    entry.error = '';
    renderPendingFiles();
    return api('POST', '/api/questions/import', importPayload(entry)).then(function (res) {
      entry.uploaded = true;
      entry.uploading = false;
      entry.status = '上传成功';
      rememberBank(res && res.bank);
      renderPendingFiles();
    }).catch(function (err) {
      entry.uploading = false;
      entry.error = '上传失败：' + err.message;
      renderPendingFiles();
    }).then(function () { return uploadPendingFiles(entries, index + 1); });
  }

  $('btn-upload-files').addEventListener('click', function () {
    var entries = pendingFiles.filter(function (entry) { return entry.raw && entry.previewed && !entry.uploaded; });
    if (entries.length === 0) {
      setMsg($('file-import-msg'), '没有已预览且待上传的文件，请先逐份点击“预览这份题目”', true);
      return;
    }
    fileUploadRunning = true;
    $('btn-check-files').disabled = true;
    renderPendingFiles();
    uploadPendingFiles(entries, 0).then(function () {
      fileUploadRunning = false;
      $('btn-check-files').disabled = false;
      var succeeded = entries.filter(function (entry) { return entry.uploaded; }).length;
      setMsg($('file-import-msg'), '逐文件上传结束：成功 ' + succeeded + ' 份，未上传 ' + (entries.length - succeeded)
        + ' 份。其他已有题库均未清空。', succeeded !== entries.length);
      renderPendingFiles();
      return Promise.all([refreshState(), refreshQuestionBanks()]);
    }).catch(function (err) {
      fileUploadRunning = false;
      $('btn-check-files').disabled = false;
      renderPendingFiles();
      setMsg($('file-import-msg'), '上传后刷新列表失败：' + err.message, true);
    });
  });

  /* ---------- AI 出题提示词 ---------- */

  var AI_PROMPT = [
    '请帮我为小学生生成一份「看电视答题解锁」题库的 JSON 文件。要求：',
    '',
    '1. 严格输出一个 JSON 对象，不要输出任何其他文字或 markdown 代码块标记。',
    '2. 一份文件只对应一个明确的学科、年级和学习进度；需要其他范围时请另生成独立文件，不要合并成一份大杂烩。文件可按“三年级数学-乘法第一阶段”“二年级语文-古诗复习”等主题拆分。',
    '3. 顶层格式：{"formatVersion":1,"version":"版本名","questions":[题目数组]}。version 用日期+范围命名，不超过 80 字符；每份 1~500 题，文件不超过 4 MB。',
    '4. 每道题包含以下字段：',
    '   - id：1~64 字符，只能包含英文字母、数字、下划线 _ 和短横线 -，在本文件内唯一，如 "g3m01"；跨文件可用相同 id',
    '   - grade：本文件对应的年级，不超过 30 字符',
    '   - subject：本文件对应的学科，不超过 30 字符',
    '   - topic：具体知识点或学习进度，不超过 80 字符',
    '   - difficulty：只能是 "简单"、"中等"、"困难" 之一',
    '   - stem：非空题干，不超过 2000 字符；同一文件内题干不得重复',
    '   - options：2~6 个非空字符串选项，每项不超过 300 字符',
    '   - answer：数字，从 0 开始的正确选项下标（例如第一个选项正确则为 0）',
    '   - explanation：非空解析，说明为什么正确答案正确，不超过 3000 字符',
    '   - source：填 "AI生成"，不超过 200 字符',
    '5. 判断题也用两个选项，例如 options:["正确","错误"]。',
    '6. 题目必须有唯一确定的正确答案，不出有争议、歧义或超纲的题。',
    '7. 本次请出 10 道题，都属于下面指定的同一范围，难度可以混合。',
    '',
    '这份文件的年级、学科和学习进度是：【在这里填写，例如：三年级数学 + 乘法口诀第一阶段】'
  ].join('\n');

  $('ai-prompt').textContent = AI_PROMPT;

  $('btn-copy-prompt').addEventListener('click', function () {
    var msgEl = $('copy-msg');
    var done = function () { setMsg(msgEl, '已复制，去粘贴给 AI 吧', false); };
    var fail = function () { setMsg(msgEl, '复制失败，请长按上方文本手动全选复制', true); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(AI_PROMPT).then(done, function () { legacyCopy(done, fail); });
    } else {
      legacyCopy(done, fail);
    }
  });

  function legacyCopy(done, fail) {
    var ta = document.createElement('textarea');
    ta.value = AI_PROMPT;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    if (ok) done(); else fail();
  }

  /* ---------- 日志 ---------- */

  function renderLogs(logs) {
    var list = $('log-list');
    clearChildren(list);
    $('logs-empty').classList.toggle('hidden', logs.length > 0);
    // 最新的显示在最上面
    for (var i = logs.length - 1; i >= 0; i--) {
      list.appendChild(el('li', null, logs[i]));
    }
  }

  boot();
})();
