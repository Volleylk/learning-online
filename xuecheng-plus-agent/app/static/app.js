/* 学成在线 AI 助手 - 前端逻辑 */
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const chat = $("chat");
  const input = $("input");
  const form = $("chat-form");

  let sessionId = localStorage.getItem("xc_session_id") || uuid();

  // ---------- 会话管理 ----------
  function uuid() {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
    });
  }

  function newConversation() {
    sessionId = uuid();
    localStorage.setItem("xc_session_id", sessionId);
    chat.innerHTML = "";
    renderWelcome();
    scrollBottom();
    refreshActive();
    loadSessions();
  }

  function renderWelcome() {
    const div = document.createElement("div");
    div.className = "welcome";
    div.innerHTML =
      "<h2>你好，我是你的 AI 学习助手 👋</h2>" +
      "<p>可以问我课程知识点，比如「解释一下 Java 的 HashMap 原理」；<br />" +
      "也可以让我推荐课程，比如「零基础想学 Spring Boot 有什么课」。</p>";
    chat.appendChild(div);
  }

  // ---------- Token ----------
  function getCookie(name) {
    const m = document.cookie.match(new RegExp("(^|; )" + name + "=([^;]*)(;|$)"));
    return m ? unescape(m[2]) : "";
  }

  // 统一取 token：URL 参数 → 门户登录 Cookie(jwt) → localStorage 旧值
  function getToken() {
    return (
      new URLSearchParams(location.search).get("token") ||
      getCookie("jwt") ||
      localStorage.getItem("xc_token") ||
      ""
    ).trim();
  }

  function loadToken() {
    // 优先级：URL ?token= → 门户登录 Cookie(jwt) → localStorage 旧值
    // 仅静默保存，不展示任何提示条
    const urlToken = new URLSearchParams(location.search).get("token");
    if (urlToken) {
      localStorage.setItem("xc_token", urlToken);
      return;
    }
    const cookieToken = getCookie("jwt");
    if (cookieToken) {
      localStorage.setItem("xc_token", cookieToken);
    }
  }

  // ---------- 消息渲染 ----------
  function addMessage(role, text) {
    const wrap = document.createElement("div");
    wrap.className = "msg " + role;

    const avatar = document.createElement("div");
    avatar.className = "avatar";
    avatar.textContent = role === "user" ? "我" : "AI";

    const bubble = document.createElement("div");
    bubble.className = "bubble";
    if (role === "assistant") {
      bubble.classList.add("md");
      bubble.innerHTML = renderMarkdown(text);
    } else {
      bubble.textContent = text;
    }

    wrap.appendChild(avatar);
    wrap.appendChild(bubble);
    chat.appendChild(wrap);
    scrollBottom();
    return wrap;
  }

  function renderCourses(container, courses) {
    if (!courses || !courses.length) return;
    const box = document.createElement("div");
    box.className = "courses";
    courses.forEach((c) => {
      const a = document.createElement("a");
      a.className = "course-card";
      a.href = c.url || "#";
      a.target = "_blank";
      a.rel = "noopener";
      a.innerHTML =
        '<span class="cover">' +
        (c.cover
          ? '<img src="' + c.cover + '" alt="cover" onerror="this.parentNode.textContent=\'课\'" />'
          : "课") +
        "</span>" +
        '<span class="info">' +
        '<span class="name">' + esc(c.name) + "</span>" +
        "</span>";
      box.appendChild(a);
    });
    container.appendChild(box);
    scrollBottom();
  }

  function renderSources(container, sources) {
    if (!sources || !sources.length) return;
    const details = document.createElement("details");
    details.className = "sources";
    details.innerHTML =
      "<summary>参考了 " + sources.length + " 条讲义片段</summary><ul>" +
      sources
        .slice(0, 5)
        .map(
          (s) =>
            "<li>" +
            esc(s.course_name || "") +
            " · " +
            esc(s.source_file || "") +
            " · 第" +
            (s.page_num || "-") +
            "页：<span style='opacity:.85'>" +
            esc((s.text || "").slice(0, 80)) +
            "…</span></li>"
        )
        .join("") +
      "</ul>";
    container.appendChild(details);
  }

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  // ---------- 轻量 Markdown 渲染 ----------
  // 只渲染白名单子集（表格/标题/列表/代码块/引用/加粗/行内代码/链接/分隔线）。
  // 原文一律先 esc() 转义，再注入白名单标签，杜绝 XSS；流式半截内容容错渲染。

  function mdInline(text) {
    // 输入已转义，按顺序做行内替换
    let s = text;
    // 1) 先保护行内代码（内部的 ** 等不被二次处理）
    const codes = [];
    s = s.replace(/`([^`]+)`/g, function (m, c) {
      codes.push(c);
      return "\u0000" + (codes.length - 1) + "\u0000";
    });
    // 2) 加粗 **text**
    s = s.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    // 3) 链接 [text](url)（仅允许 http/https）
    s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (m, t, u) {
      return /^https?:\/\//i.test(u)
        ? '<a href="' + u + '" target="_blank" rel="noopener">' + t + "</a>"
        : t;
    });
    // 4) 还原行内代码
    s = s.replace(/\u0000(\d+)\u0000/g, function (m, i) {
      return "<code>" + codes[+i] + "</code>";
    });
    return s;
  }

  function isTableRow(line) {
    return /^\s*\|.*\|\s*$/.test(line);
  }
  function isTableSep(line) {
    return /^\s*\|?\s*:?-+:?(\s*\|?\s*:?-+:?)*\|?\s*$/.test(line.trim()) && /\s-/.test(line.replace(/\|/g, " "));
  }
  function parseRow(line) {
    const t = line.trim().replace(/^\||\|$/g, "");
    return t.split("|").map(function (c) {
      const v = c.trim();
      return v ? mdInline(esc(v)) : "";
    });
  }

  function renderMarkdown(text) {
    if (!text) return "";
    const lines = String(text).split("\n");
    const out = [];
    let i = 0;
    let inCode = false;
    let codeBuf = [];

    while (i < lines.length) {
      const line = lines[i];

      // 代码块 ``` ... ```
      if (/^```/.test(line.trim())) {
        if (inCode) {
          out.push("<pre><code>" + esc(codeBuf.join("\n")) + "</code></pre>");
          codeBuf = [];
          inCode = false;
        } else {
          inCode = true;
        }
        i++;
        continue;
      }
      if (inCode) {
        codeBuf.push(line);
        i++;
        continue;
      }

      // 表格：当前行是分隔行且上一行是表头行
      if (isTableSep(line) && i > 0 && isTableRow(lines[i - 1])) {
        // 上一行表头可能已被当作普通段落输出，先移除，避免重复
        const headerPara = "<p>" + mdInline(esc(lines[i - 1])) + "</p>";
        if (out.length && out[out.length - 1] === headerPara) {
          out.pop();
        }
        const header = parseRow(lines[i - 1]);
        i++; // 跳过分隔行
        const rows = [];
        while (i < lines.length && isTableRow(lines[i])) {
          rows.push(parseRow(lines[i]));
          i++;
        }
        out.push(buildTable(header, rows));
        continue;
      }

      // 标题
      const hm = /^(#{1,4})\s+(.*)$/.exec(line);
      if (hm) {
        const level = hm[1].length >= 3 ? 4 : 3; // ## → h3, ###/#### → h4
        out.push("<h" + level + ">" + mdInline(esc(hm[2])) + "</h" + level + ">");
        i++;
        continue;
      }

      // 引用 > ...（合并连续引用行）
      if (/^>\s?/.test(line)) {
        const q = [];
        while (i < lines.length && /^>\s?/.test(lines[i])) {
          q.push(mdInline(esc(lines[i].replace(/^>\s?/, ""))));
          i++;
        }
        out.push("<blockquote>" + q.join("<br>") + "</blockquote>");
        continue;
      }

      // 无序列表 - / *
      if (/^[-*]\s+/.test(line)) {
        const items = [];
        while (i < lines.length && /^[-*]\s+/.test(lines[i])) {
          items.push("<li>" + mdInline(esc(lines[i].replace(/^[-*]\s+/, ""))) + "</li>");
          i++;
        }
        out.push("<ul>" + items.join("") + "</ul>");
        continue;
      }

      // 有序列表 1. / 1、
      if (/^\d+[.、]\s+/.test(line)) {
        const items = [];
        while (i < lines.length && /^\d+[.、]\s+/.test(lines[i])) {
          items.push("<li>" + mdInline(esc(lines[i].replace(/^\d+[.、]\s+/, ""))) + "</li>");
          i++;
        }
        out.push("<ol>" + items.join("") + "</ol>");
        continue;
      }

      // 分隔线
      if (/^\s*([-*_])\1{2,}\s*$/.test(line)) {
        out.push("<hr>");
        i++;
        continue;
      }

      // 空行
      if (/^\s*$/.test(line)) {
        i++;
        continue;
      }

      // 普通段落（流式半截表格行/未闭合标记原样显示）
      out.push("<p>" + mdInline(esc(line)) + "</p>");
      i++;
    }

    if (inCode) {
      out.push("<pre><code>" + esc(codeBuf.join("\n")) + "</code></pre>");
    }
    return out.join("\n");
  }

  function buildTable(header, rows) {
    const thead =
      "<thead><tr>" + header.map(function (h) { return "<th>" + h + "</th>"; }).join("") + "</tr></thead>";
    const tbody =
      "<tbody>" +
      rows
        .map(function (r) {
          return "<tr>" + r.map(function (c) { return "<td>" + c + "</td>"; }).join("") + "</tr>";
        })
        .join("") +
      "</tbody>";
    return '<div class="md-table-wrap"><table>' + thead + tbody + "</table></div>";
  }

  function scrollBottom() {
    chat.scrollTop = chat.scrollHeight;
  }

  // ---------- 历史会话侧栏 ----------
  async function loadSessions() {
    const token = getToken();
    if (!token) return;
    try {
      const resp = await fetch("/api/sessions?limit=30", {
        headers: { Authorization: "Bearer " + token },
      });
      if (!resp.ok) return;
      const list = await resp.json();
      const ul = $("session-list");
      ul.innerHTML = "";
      if (!list.length) {
        const empty = document.createElement("li");
        empty.className = "session-empty";
        empty.textContent = "暂无历史会话";
        ul.appendChild(empty);
        return;
      }
      list.forEach((s) => {
        const li = document.createElement("li");
        li.className = "session-item" + (s.session_id === sessionId ? " active" : "");
        li.dataset.sid = s.session_id;
        li.innerHTML =
          '<span class="s-title">' +
          esc(s.first_message || "（空会话）") +
          "</span>" +
          '<span class="s-time">' +
          esc((s.last_time || "").replace("T", " ").slice(5, 16)) +
          "</span>";
        li.addEventListener("click", () => openSession(s.session_id));
        ul.appendChild(li);
      });
    } catch (e) {
      /* 列表加载失败静默 */
    }
  }

  async function openSession(sid) {
    const token = getToken();
    if (!token) return;
    try {
      const resp = await fetch("/api/sessions/" + encodeURIComponent(sid) + "/messages", {
        headers: { Authorization: "Bearer " + token },
      });
      if (!resp.ok) return;
      const msgs = await resp.json();
      sessionId = sid;
      localStorage.setItem("xc_session_id", sid);
      chat.innerHTML = "";
      msgs.forEach((m) => {
        const wrap = addMessage(m.role === "user" ? "user" : "assistant", m.content);
        // 历史消息若带课程卡片数据，重新渲染（与流式一致）
        if (m.courses && m.courses.length) renderCourses(wrap, m.courses);
      });
      if (!msgs.length) renderWelcome();
      scrollBottom();
      refreshActive();
    } catch (e) {
      /* 历史加载失败静默 */
    }
  }

  function refreshActive() {
    document.querySelectorAll(".session-item").forEach((li) => {
      li.classList.toggle("active", li.dataset.sid === sessionId);
    });
  }

  // ---------- 发送（SSE 流式）----------
  async function send() {
    const message = input.value.trim();
    const token = getToken();
    if (!message) return;
    if (!token) {
      addMessage("assistant", "未检测到门户登录态，请先在门户登录后重新进入 AI 助手。");
      return;
    }

    input.value = "";
    addMessage("user", message);

    const wrap = addMessage("assistant", "");
    const bubble = wrap.querySelector(".bubble");
    bubble.classList.add("streaming");
    bubble.textContent = "思考中…";
    scrollBottom();

    let full = "";
    let gotAnswer = false;

    try {
      const resp = await fetch("/api/chat", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + token,
        },
        body: JSON.stringify({ message, session_id: sessionId }),
      });

      if (resp.status === 401) {
        const data = await resp.json().catch(() => ({}));
        bubble.classList.remove("streaming");
        bubble.textContent = "登录态校验失败：" + (data.detail || "无效 token");
        return;
      }
      if (!resp.ok) {
        const data = await resp.json().catch(() => ({}));
        bubble.classList.remove("streaming");
        bubble.textContent = "请求出错（" + resp.status + "）：" + (data.detail || "");
        return;
      }

      // 逐块读取 SSE 事件流
      const reader = resp.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buf = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf("\n\n")) >= 0) {
          const raw = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          const line = raw.split("\n").find((l) => l.startsWith("data: "));
          if (!line) continue;
          let ev;
          try {
            ev = JSON.parse(line.slice(6));
          } catch (e) {
            continue;
          }
          if (ev.type === "thinking") {
            if (!gotAnswer) {
              bubble.textContent = ev.text + "…";
              scrollBottom();
            }
          } else if (ev.type === "answer") {
            gotAnswer = true;
            full += ev.delta;
            bubble.classList.add("md");
            bubble.innerHTML = renderMarkdown(full);
            scrollBottom();
          } else if (ev.type === "sources") {
            renderSources(wrap, ev.sources);
          } else if (ev.type === "courses") {
            renderCourses(wrap, ev.courses);
          } else if (ev.type === "done") {
            if (ev.session_id) {
              sessionId = ev.session_id;
              localStorage.setItem("xc_session_id", sessionId);
            }
            if (ev.courses && ev.courses.length) renderCourses(wrap, ev.courses);
            if (ev.sources && ev.sources.length) renderSources(wrap, ev.sources);
          }
        }
      }
      bubble.classList.remove("streaming");
      if (full) {
        bubble.classList.add("md");
        bubble.innerHTML = renderMarkdown(full);
      } else {
        bubble.textContent = "(无回答)";
      }
      scrollBottom();
    } catch (e) {
      bubble.classList.remove("streaming");
      bubble.textContent = "网络错误，无法连接 AI 服务：" + e.message;
    }
  }

  // ---------- 事件绑定 ----------
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    send();
  });
  $("btn-new").addEventListener("click", newConversation);
  $("btn-refresh").addEventListener("click", loadSessions);

  // ---------- 初始化 ----------
  loadToken();
  renderWelcome();
  loadSessions();
})();
