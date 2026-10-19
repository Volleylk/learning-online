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
    bubble.textContent = text;

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
        '<span class="intro">' + esc(c.intro || "") + "</span>" +
        "</span>" +
        '<span class="go">→</span>';
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
      msgs.forEach((m) => addMessage(m.role === "user" ? "user" : "assistant", m.content));
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
            bubble.textContent = full;
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
      bubble.textContent = full || "(无回答)";
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
