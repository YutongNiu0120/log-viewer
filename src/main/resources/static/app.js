(function () {
  const STARRED_PROJECTS_KEY = "logViewer.starredProjects";
  const LOG_FONT_SIZE_KEY = "logViewer.fontSizePx";
  const FILTER_DRAWER_COLLAPSED_KEY = "logViewer.filterDrawerCollapsed";
  const SIDEBAR_WIDTH_KEY = "logViewer.sidebarWidthPx";
  const LOG_FONT_SIZE_DEFAULT = 12;
  const LOG_FONT_SIZE_MIN = 10;
  const LOG_FONT_SIZE_MAX = 22;
  const LOG_FONT_SIZE_STEP = 1;
  const TAB_IDLE_PAUSE_MS = 30 * 60 * 1000;
  const TAB_IDLE_CHECK_MS = 60 * 1000;

  const state = {
    bootstrap: null,
    serverId: null,
    projects: [],
    projectPath: null,
    logType: "normal",
    files: [],
    currentFile: null,
    currentFileMeta: null,
    fileSize: 0,
    startOffset: 0,
    endOffset: 0,
    autoFollow: true,
    ws: null,
    wsConnected: false,
    tabs: [],
    activeTabId: null,
    nextTabSeq: 1,
    tabIdleTimer: null,
    searchHits: [],
    searchCollapsedGroups: {},
    starredProjectPaths: {},
    starredProjectOrder: [],
    draggingStarProjectPath: null,
    suppressNextProjectClick: false,
    startupDefaultProjectDone: false,
    pendingAllSearchConfirm: null,
    pendingServerSwitchConfirm: null,
    toastSeq: 1,
    loadingPrev: false,
    l1FilterInitialized: false,
    drawerCollapsed: false,
    sidebarWidthPx: 250,
    lastFilesRefreshAtEpochMs: 0,
    searchCursor: -1,
    lastSearchKeyword: "",
    lastSearchScope: "",
    logBuffer: "",
    focusLineText: "",
    viewerHighlightKeyword: "",
    viewerHighlightCaseSensitive: false,
    logFontSizePx: LOG_FONT_SIZE_DEFAULT
  };

  const $ = (id) => document.getElementById(id);
  const els = {
    serverSelect: $("serverSelect"),
    serverMeta: $("serverMeta"),
    appBody: $("appBody"),
    sidebar: document.querySelector(".sidebar"),
    sidebarResizer: $("sidebarResizer"),
    refreshProjectsBtn: $("refreshProjectsBtn"),
    testConnBtn: $("testConnBtn"),
    configBtn: $("configBtn"),
    l1Filter: $("l1Filter"),
    projectFilter: $("projectFilter"),
    projectTree: $("projectTree"),
    fileFilter: $("fileFilter"),
    fileSelect: $("fileSelect"),
    openLatestBtn: $("openLatestBtn"),
    fileTypeTabs: $("fileTypeTabs"),
    closeRightTabsBtn: $("closeRightTabsBtn"),
    closeOtherTabsBtn: $("closeOtherTabsBtn"),
    fileMeta: $("fileMeta"),
    currentProjectLabel: $("currentProjectLabel"),
    currentFileLabel: $("currentFileLabel"),
    currentLogTypeLabel: $("currentLogTypeLabel"),
    modePill: $("modePill"),
    logTabs: $("logTabs"),
    logFontDownBtn: $("logFontDownBtn"),
    logFontResetBtn: $("logFontResetBtn"),
    logFontUpBtn: $("logFontUpBtn"),
    logFontSizeLabel: $("logFontSizeLabel"),
    statusPill: $("statusPill"),
    backToBottomBtn: $("backToBottomBtn"),
    logViewer: $("logViewer"),
    logContent: $("logContent"),
    searchKeyword: $("searchKeyword"),
    searchScope: $("searchScope"),
    caseSensitive: $("caseSensitive"),
    searchBtn: $("searchBtn"),
    searchPrevBtn: $("searchPrevBtn"),
    searchNextBtn: $("searchNextBtn"),
    searchResetBtn: $("searchResetBtn"),
    searchConfirmHint: $("searchConfirmHint"),
    searchNavInfo: $("searchNavInfo"),
    searchSummary: $("searchSummary"),
    searchExpandAllBtn: $("searchExpandAllBtn"),
    searchCollapseAllBtn: $("searchCollapseAllBtn"),
    searchResults: $("searchResults"),
    configModal: $("configModal"),
    closeConfigBtn: $("closeConfigBtn"),
    serverFormList: $("serverFormList"),
    addServerFormBtn: $("addServerFormBtn"),
    saveConfigBtn: $("saveConfigBtn"),
    reloadConfigBtn: $("reloadConfigBtn"),
    toastContainer: $("toastContainer"),
    filterDrawer: $("filterDrawer"),
    drawerToggleBtn: $("drawerToggleBtn"),
    realtimeSwitch: $("realtimeSwitch")
  };

  function setStatus(text, kind = "normal") {
    els.statusPill.textContent = text;
    els.statusPill.style.borderColor = kind === "error" ? "#efb7b7" : "#d7c9af";
    els.statusPill.style.color = kind === "error" ? "#b91c1c" : "#594f41";
    els.statusPill.style.background = kind === "error" ? "#fff2f2" : "#f0ece2";
    const tab = getActiveTab ? getActiveTab() : null;
    if (tab) {
      tab.uiStatusText = text;
      tab.uiStatusKind = kind;
    }
  }

  function notify(message, kind = "info", timeoutMs = 2800) {
    if (!els.toastContainer || !message) return;
    const item = document.createElement("div");
    item.className = `toast ${kind}`;
    item.dataset.toastId = `toast-${state.toastSeq++}`;
    item.textContent = String(message);
    els.toastContainer.appendChild(item);
    const remove = () => {
      if (item && item.parentElement) {
        item.parentElement.removeChild(item);
      }
    };
    setTimeout(remove, Math.max(1200, timeoutMs || 2800));
  }

  function logTypeLabel(logType) {
    return logType === "error" ? "异常" : "业务";
  }

  function updateCurrentLogTypeLabel(value) {
    if (els.currentLogTypeLabel) {
      els.currentLogTypeLabel.textContent = logTypeLabel(value);
    }
  }

  function renderLogTypeTabs() {
    document.querySelectorAll(".file-type-tab[data-log-type]").forEach((btn) => {
      const type = btn.dataset.logType === "error" ? "error" : "normal";
      btn.classList.toggle("active", type === state.logType);
    });
  }

  function getVisibleTabs() {
    return state.tabs.filter((t) => !isPlaceholderTab(t));
  }

  function getModeInfo() {
    const tab = getActiveTab();
    if (!tab || !tab.projectPath) {
      return { text: "未选择项目", cls: "history", canResume: false, resumeToBottom: false };
    }
    if (tab.pausedByIdle) {
      return { text: "实时已暂停", cls: "warn", canResume: true, resumeToBottom: false };
    }
    if (tab.realtimeWanted && tab.wsConnected) {
      if (state.autoFollow) {
        return { text: "实时追踪", cls: "live", canResume: false, resumeToBottom: false };
      }
      return { text: "实时追踪（脱离底部）", cls: "live", canResume: true, resumeToBottom: true };
    }
    if (tab.realtimeWanted && !tab.wsConnected) {
      return { text: "实时重连中", cls: "warn", canResume: true, resumeToBottom: false };
    }
    return { text: "历史浏览", cls: "history", canResume: false, resumeToBottom: false };
  }

  function updateModeUI() {
    if (!els.modePill) return;
    const info = getModeInfo();
    els.modePill.textContent = info.text;
    els.modePill.className = `pill mode-pill ${info.cls}`;
    if (els.realtimeSwitch) {
      const tab = getActiveTab();
      const enabled = !!(tab && tab.realtimeWanted && !tab.pausedByIdle);
      els.realtimeSwitch.checked = enabled;
      els.realtimeSwitch.disabled = !state.projectPath;
    }
  }

  function updateTabActionButtons() {
    const visible = getVisibleTabs();
    const activeIdx = visible.findIndex((t) => t.id === state.activeTabId);
    if (els.closeOtherTabsBtn) {
      els.closeOtherTabsBtn.disabled = visible.length <= 1 || activeIdx < 0;
    }
    if (els.closeRightTabsBtn) {
      els.closeRightTabsBtn.disabled = visible.length <= 1 || activeIdx < 0 || activeIdx >= visible.length - 1;
    }
  }

  function updateActionAvailability() {
    const hasProject = !!state.projectPath;
    const hasFiles = hasProject && Array.isArray(state.files) && state.files.length > 0;
    document.querySelectorAll(".file-type-tab[data-log-type]").forEach((btn) => {
      btn.disabled = !hasProject;
    });
    if (els.fileFilter) els.fileFilter.disabled = !hasProject;
    if (els.fileSelect) els.fileSelect.disabled = !hasFiles;
    if (els.openLatestBtn) els.openLatestBtn.disabled = !hasFiles;
    if (els.searchKeyword) els.searchKeyword.disabled = !hasProject;
    if (els.searchScope) els.searchScope.disabled = !hasProject;
    if (els.caseSensitive) els.caseSensitive.disabled = !hasProject;
    if (els.searchBtn) els.searchBtn.disabled = !hasProject;
    if (els.searchResetBtn) els.searchResetBtn.disabled = !hasProject;
    updateSearchNavigationUI();
    updateTabActionButtons();
    updateModeUI();
  }

  async function resumeRealtimeView() {
    const info = getModeInfo();
    if (!state.projectPath) {
      notify("请先选择项目", "warn");
      return;
    }
    if (info.resumeToBottom) {
      scrollToBottom();
      notify("已回到底部", "info", 1600);
      return;
    }
    await openLatestRealtime();
    notify("已恢复实时追踪", "info", 1800);
  }

  async function setRealtimeFollowEnabled(enabled) {
    const tab = getActiveTab();
    if (!tab || !state.projectPath) {
      if (els.realtimeSwitch) els.realtimeSwitch.checked = false;
      return;
    }
    if (enabled) {
      await resumeRealtimeView();
      return;
    }
    shutdownTabSocket(tab, true);
    setTabStatus(tab, "已切换为历史浏览");
    updateModeUI();
    syncStateToActiveTab();
  }

  function createEmptyTab() {
    const id = `tab-${state.nextTabSeq++}`;
    return {
      id,
      title: "未选择项目",
      serverId: state.serverId || null,
      projectPath: null,
      projectName: null,
      logType: "normal",
      files: [],
      currentFile: null,
      currentFileMeta: null,
      fileSize: 0,
      startOffset: 0,
      endOffset: 0,
      autoFollow: true,
      logBuffer: "",
      loadingPrev: false,
      focusLineText: "",
      viewerHighlightKeyword: "",
      viewerHighlightCaseSensitive: false,
      searchHits: [],
      searchCollapsedGroups: {},
      searchCursor: -1,
      lastSearchKeyword: "",
      lastSearchScope: "",
      searchKeywordInput: "",
      searchScopeValue: "currentFile",
      caseSensitiveValue: false,
      fileFilterInput: "",
      ws: null,
      wsConnected: false,
      realtimeWanted: false,
      pausedByIdle: false,
      lastRealtimeMode: "latest",
      lastRealtimeFile: null,
      lastActivatedAtEpochMs: Date.now(),
      uiStatusText: "未连接",
      uiStatusKind: "normal"
    };
  }

  function isPlaceholderTab(tab) {
    return !!tab && !tab.projectPath;
  }

  function ensureAtLeastOneTab() {
    if (state.tabs.length > 0) return;
    const tab = createEmptyTab();
    state.tabs.push(tab);
    state.activeTabId = tab.id;
    loadStateFromTab(tab);
    renderLogTabs();
    updateActionAvailability();
  }

  function getActiveTab() {
    return state.tabs.find((t) => t.id === state.activeTabId) || null;
  }

  function syncStateToActiveTab() {
    const tab = getActiveTab();
    if (!tab) return;
    tab.serverId = state.serverId;
    tab.projectPath = state.projectPath;
    tab.logType = state.logType;
    tab.files = Array.isArray(state.files) ? state.files.slice() : [];
    tab.currentFile = state.currentFile;
    tab.currentFileMeta = state.currentFileMeta ? { ...state.currentFileMeta } : null;
    tab.fileSize = state.fileSize;
    tab.startOffset = state.startOffset;
    tab.endOffset = state.endOffset;
    tab.autoFollow = state.autoFollow;
    tab.logBuffer = state.logBuffer || "";
    tab.loadingPrev = !!state.loadingPrev;
    tab.focusLineText = state.focusLineText || "";
    tab.viewerHighlightKeyword = state.viewerHighlightKeyword || "";
    tab.viewerHighlightCaseSensitive = !!state.viewerHighlightCaseSensitive;
    tab.searchHits = Array.isArray(state.searchHits) ? state.searchHits.slice() : [];
    tab.searchCollapsedGroups = { ...(state.searchCollapsedGroups || {}) };
    tab.searchCursor = state.searchCursor;
    tab.lastSearchKeyword = state.lastSearchKeyword || "";
    tab.lastSearchScope = state.lastSearchScope || "";
    tab.searchKeywordInput = els.searchKeyword ? els.searchKeyword.value : "";
    tab.searchScopeValue = els.searchScope ? els.searchScope.value : "currentFile";
    tab.caseSensitiveValue = !!(els.caseSensitive && els.caseSensitive.checked);
    tab.fileFilterInput = els.fileFilter ? els.fileFilter.value : "";
    tab.ws = state.ws || tab.ws || null;
    tab.wsConnected = !!state.wsConnected;
    tab.title = buildTabTitle(tab);
  }

  function loadStateFromTab(tab) {
    if (!tab) return;
    state.serverId = tab.serverId || state.serverId;
    state.projectPath = tab.projectPath || null;
    state.logType = tab.logType || "normal";
    state.files = Array.isArray(tab.files) ? tab.files.slice() : [];
    state.currentFile = tab.currentFile || null;
    state.currentFileMeta = tab.currentFileMeta ? { ...tab.currentFileMeta } : null;
    state.fileSize = Number(tab.fileSize || 0);
    state.startOffset = Number(tab.startOffset || 0);
    state.endOffset = Number(tab.endOffset || 0);
    state.autoFollow = tab.autoFollow !== false;
    state.logBuffer = tab.logBuffer || "";
    state.loadingPrev = !!tab.loadingPrev;
    state.focusLineText = tab.focusLineText || "";
    state.viewerHighlightKeyword = tab.viewerHighlightKeyword || "";
    state.viewerHighlightCaseSensitive = !!tab.viewerHighlightCaseSensitive;
    state.searchHits = Array.isArray(tab.searchHits) ? tab.searchHits.slice() : [];
    state.searchCollapsedGroups = { ...(tab.searchCollapsedGroups || {}) };
    state.searchCursor = Number.isFinite(tab.searchCursor) ? tab.searchCursor : -1;
    state.lastSearchKeyword = tab.lastSearchKeyword || "";
    state.lastSearchScope = tab.lastSearchScope || "";
    state.ws = tab.ws || null;
    state.wsConnected = !!tab.wsConnected;
    if (els.searchKeyword) els.searchKeyword.value = tab.searchKeywordInput || "";
    if (els.searchScope) els.searchScope.value = tab.searchScopeValue || "currentFile";
    if (els.caseSensitive) els.caseSensitive.checked = !!tab.caseSensitiveValue;
    if (els.fileFilter) els.fileFilter.value = tab.fileFilterInput || "";
    if (els.currentProjectLabel) els.currentProjectLabel.textContent = tab.projectPath || "-";
    if (els.currentFileLabel) els.currentFileLabel.textContent = tab.currentFile || "-";
    updateCurrentLogTypeLabel(state.logType);
    renderLogTypeTabs();
    renderFiles();
    if (state.currentFile && els.fileSelect) {
      els.fileSelect.value = state.currentFile;
      updateFileMeta();
    }
    renderLogBuffer();
    renderSearchResults(state.lastSearchKeyword || (els.searchKeyword ? els.searchKeyword.value.trim() : ""));
    updateSearchNavigationUI();
    updateSearchGroupToolButtons();
    applyTabStatusToUI(tab);
    updateActionAvailability();
  }

  function buildTabTitle(tab) {
    if (!tab || !tab.projectPath) return "未选择项目";
    const base = tab.projectName || (tab.projectPath.split("/").pop() || tab.projectPath);
    return `${base} [${logTypeLabel(tab.logType)}]`;
  }

  function renderLogTabs() {
    if (!els.logTabs) return;
    els.logTabs.innerHTML = "";
    const visibleTabs = state.tabs.filter((t) => !isPlaceholderTab(t));
    if (els.logTabs.parentElement) {
      els.logTabs.parentElement.hidden = visibleTabs.length === 0;
    }
    for (const tab of visibleTabs) {
      const item = document.createElement("div");
      item.className = "log-tab-item" + (tab.id === state.activeTabId ? " active" : "");
      item.dataset.tabId = tab.id;
      const stateLabel = tab.pausedByIdle ? "已暂停" : (tab.wsConnected ? "实时" : (tab.realtimeWanted ? "待连" : "静态"));
      const stateClass = tab.pausedByIdle ? "paused" : (tab.wsConnected ? "live" : "");
      item.innerHTML = `
        <span class="log-tab-title" title="${escapeHtml(buildTabTitle(tab))}">${escapeHtml(buildTabTitle(tab))}</span>
        <span class="log-tab-state ${stateClass}">${stateLabel}</span>
        <button type="button" class="log-tab-close" title="关闭">×</button>
      `;
      els.logTabs.appendChild(item);
    }
    updateTabActionButtons();
  }

  async function activateTab(tabId) {
    const target = state.tabs.find((t) => t.id === tabId);
    if (!target || target.id === state.activeTabId) {
      const current = getActiveTab();
      if (current) {
        current.lastActivatedAtEpochMs = Date.now();
        if (current.pausedByIdle && current.realtimeWanted) {
          await resumeTabRealtime(current);
        }
      }
      renderLogTabs();
      return;
    }
    syncStateToActiveTab();
    state.activeTabId = target.id;
    target.lastActivatedAtEpochMs = Date.now();
    if (els.serverSelect && target.serverId && els.serverSelect.value !== target.serverId) {
      els.serverSelect.value = target.serverId;
      state.serverId = target.serverId;
      updateServerMeta();
      await loadProjects();
    } else {
      state.serverId = target.serverId || state.serverId;
    }
    loadStateFromTab(target);
    renderProjectTree();
    renderLogTabs();
    if (target.pausedByIdle && target.realtimeWanted) {
      await resumeTabRealtime(target);
    }
  }

  function createProjectTab(project, logType) {
    const tab = createEmptyTab();
    tab.serverId = state.serverId;
    tab.projectPath = project.projectPath;
    tab.projectName = project.projectName;
    tab.logType = logType || "normal";
    tab.title = buildTabTitle(tab);
    return tab;
  }

  async function openProjectInTab(project, targetLogType = "normal") {
    const serverId = state.serverId;
    const logType = targetLogType === "error" ? "error" : "normal";
    let tab = state.tabs.find((t) => t.serverId === serverId && t.projectPath === project.projectPath && t.logType === logType);
    let createdNew = false;
    if (!tab) {
      const active = getActiveTab();
      if (active && isPlaceholderTab(active)) {
        tab = active;
        createdNew = true;
      } else {
        syncStateToActiveTab();
        tab = createProjectTab(project, logType);
        state.tabs.push(tab);
        createdNew = true;
      }
    }
    const previousFileName = tab && tab.currentFile ? tab.currentFile : null;
    const hadCurrentFile = !!previousFileName;
    tab.serverId = serverId;
    tab.projectPath = project.projectPath;
    tab.projectName = project.projectName;
    tab.logType = logType;
    tab.title = buildTabTitle(tab);
    await activateTab(tab.id);
    state.logType = logType;
    updateCurrentLogTypeLabel(logType);
    state.projectPath = project.projectPath;
    els.currentProjectLabel.textContent = project.projectPath;
    syncStateToActiveTab();
    renderProjectTree();
    await loadFiles();
    const fileStillExists = !!(previousFileName && state.files.some((f) => f.fileName === previousFileName));
    if (state.files.length && (createdNew || !hadCurrentFile || !fileStillExists)) {
      await openLatestRealtime();
    }
  }

  function closeTab(tabId) {
    const idx = state.tabs.findIndex((t) => t.id === tabId);
    if (idx < 0) return;
    const [tab] = state.tabs.splice(idx, 1);
    shutdownTabSocket(tab, false);
    if (state.activeTabId === tabId) {
      if (!state.tabs.length) {
        const next = createEmptyTab();
        next.serverId = state.serverId;
        state.tabs.push(next);
      }
      state.activeTabId = state.tabs[Math.max(0, idx - 1)] ? state.tabs[Math.max(0, idx - 1)].id : state.tabs[0].id;
      const active = getActiveTab();
      if (active) loadStateFromTab(active);
      renderProjectTree();
    }
    renderLogTabs();
    updateActionAvailability();
  }

  function closeOtherTabs() {
    const activeId = state.activeTabId;
    const closeIds = getVisibleTabs().filter((t) => t.id !== activeId).map((t) => t.id);
    closeIds.forEach((id) => closeTab(id));
    updateActionAvailability();
  }

  function closeRightTabs() {
    const visible = getVisibleTabs();
    const activeIdx = visible.findIndex((t) => t.id === state.activeTabId);
    if (activeIdx < 0) return;
    const closeIds = visible.slice(activeIdx + 1).map((t) => t.id);
    closeIds.forEach((id) => closeTab(id));
    updateActionAvailability();
  }

  function applyTabStatusToUI(tab) {
    if (!tab) return;
    if (tab.pausedByIdle) {
      setStatus("实时已暂停（30分钟未激活）");
      updateModeUI();
      return;
    }
    if (tab.uiStatusText) {
      setStatus(tab.uiStatusText, tab.uiStatusKind || "normal");
      updateModeUI();
      return;
    }
    setStatus(tab.wsConnected ? "实时追踪中" : "未连接");
    updateModeUI();
  }

  function setTabStatus(tab, text, kind = "normal") {
    if (!tab) return;
    tab.uiStatusText = text;
    tab.uiStatusKind = kind;
    if (tab.id === state.activeTabId) {
      setStatus(text, kind);
      updateModeUI();
    }
    renderLogTabs();
  }

  function trimTextBuffer(value, trimHead = true) {
    const maxChars = 1000000;
    const text = value || "";
    if (text.length <= maxChars) return text;
    return trimHead ? text.slice(text.length - maxChars) : text.slice(0, maxChars);
  }

  function appendTextToTabBuffer(tab, text, trimHead = true) {
    if (!tab || !text) return;
    tab.logBuffer = trimTextBuffer((tab.logBuffer || "") + text, trimHead);
  }

  function prependTextToTabBuffer(tab, text) {
    if (!tab || !text) return;
    tab.logBuffer = trimTextBuffer(text + (tab.logBuffer || ""), false);
  }

  function startTabIdleMonitor() {
    if (state.tabIdleTimer) return;
    state.tabIdleTimer = setInterval(() => {
      const now = Date.now();
      for (const tab of state.tabs) {
        if (!tab || tab.id === state.activeTabId) continue;
        if (!tab.realtimeWanted || tab.pausedByIdle) continue;
        if (!tab.ws || (tab.ws.readyState !== WebSocket.OPEN && tab.ws.readyState !== WebSocket.CONNECTING)) continue;
        if (now - (tab.lastActivatedAtEpochMs || now) < TAB_IDLE_PAUSE_MS) continue;
        shutdownTabSocket(tab, false);
        tab.pausedByIdle = true;
        tab.wsConnected = false;
        tab.uiStatusText = "实时已暂停（30分钟未激活）";
        tab.uiStatusKind = "normal";
      }
      renderLogTabs();
      const active = getActiveTab();
      if (active) applyTabStatusToUI(active);
    }, TAB_IDLE_CHECK_MS);
  }

  async function resumeTabRealtime(tab) {
    if (!tab || !tab.realtimeWanted) return;
    tab.pausedByIdle = false;
    if (tab.id !== state.activeTabId) return;
    if (!tab.projectPath) return;
    if (tab.lastRealtimeMode === "file" && tab.lastRealtimeFile) {
      await openFile(tab.lastRealtimeFile, false);
    } else {
      await openLatestRealtime();
    }
  }

  function shutdownTabSocket(tab, markDesiredOff) {
    if (!tab) return;
    const ws = tab.ws;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      try {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "close_stream" }));
        }
      } catch (e) {
        console.warn(e);
      }
      try {
        ws.close();
      } catch (e) {
        console.warn(e);
      }
    }
    tab.ws = null;
    tab.wsConnected = false;
    if (markDesiredOff) {
      tab.realtimeWanted = false;
      tab.pausedByIdle = false;
    }
    if (tab.id === state.activeTabId) {
      state.ws = null;
      state.wsConnected = false;
    }
    renderLogTabs();
    updateModeUI();
  }

  function clampLogFontSize(v) {
    const n = Number(v);
    if (!Number.isFinite(n)) return LOG_FONT_SIZE_DEFAULT;
    return Math.max(LOG_FONT_SIZE_MIN, Math.min(LOG_FONT_SIZE_MAX, Math.round(n)));
  }

  function applyLogFontSize(px) {
    const size = clampLogFontSize(px);
    state.logFontSizePx = size;
    if (els.logContent) {
      els.logContent.style.fontSize = `${size}px`;
    }
    if (els.logFontSizeLabel) {
      els.logFontSizeLabel.textContent = `${size}px`;
    }
    if (els.logFontDownBtn) {
      els.logFontDownBtn.disabled = size <= LOG_FONT_SIZE_MIN;
    }
    if (els.logFontUpBtn) {
      els.logFontUpBtn.disabled = size >= LOG_FONT_SIZE_MAX;
    }
  }

  function persistLogFontSize(px) {
    try {
      localStorage.setItem(LOG_FONT_SIZE_KEY, String(clampLogFontSize(px)));
    } catch (e) {
      console.warn(e);
    }
  }

  function loadLogFontSizePreference() {
    try {
      const raw = localStorage.getItem(LOG_FONT_SIZE_KEY);
      return clampLogFontSize(raw == null ? LOG_FONT_SIZE_DEFAULT : raw);
    } catch (e) {
      console.warn(e);
      return LOG_FONT_SIZE_DEFAULT;
    }
  }

  function loadFilterDrawerCollapsedPreference() {
    try {
      return localStorage.getItem(FILTER_DRAWER_COLLAPSED_KEY) === "1";
    } catch (e) {
      console.warn(e);
      return false;
    }
  }

  function persistFilterDrawerCollapsedPreference(collapsed) {
    try {
      localStorage.setItem(FILTER_DRAWER_COLLAPSED_KEY, collapsed ? "1" : "0");
    } catch (e) {
      console.warn(e);
    }
  }

  function clampSidebarWidth(v) {
    const n = Number(v);
    const min = 220;
    const max = Math.max(min, Math.min(560, Math.floor(window.innerWidth * 0.45)));
    if (!Number.isFinite(n)) return 250;
    return Math.max(min, Math.min(max, Math.round(n)));
  }

  function loadSidebarWidthPreference() {
    try {
      const raw = localStorage.getItem(SIDEBAR_WIDTH_KEY);
      return clampSidebarWidth(raw == null ? 250 : raw);
    } catch (e) {
      console.warn(e);
      return 250;
    }
  }

  function persistSidebarWidthPreference(px) {
    try {
      localStorage.setItem(SIDEBAR_WIDTH_KEY, String(clampSidebarWidth(px)));
    } catch (e) {
      console.warn(e);
    }
  }

  function applySidebarWidth(px) {
    const next = clampSidebarWidth(px);
    state.sidebarWidthPx = next;
    if (els.appBody) {
      els.appBody.style.setProperty("--sidebar-w", `${next}px`);
    }
    persistSidebarWidthPreference(next);
  }

  function applyFilterDrawerCollapsed(collapsed) {
    state.drawerCollapsed = !!collapsed;
    if (els.appBody) {
      els.appBody.classList.toggle("drawer-collapsed", state.drawerCollapsed);
    }
    if (els.filterDrawer) {
      els.filterDrawer.classList.toggle("collapsed", state.drawerCollapsed);
    }
    if (els.drawerToggleBtn) {
      els.drawerToggleBtn.textContent = state.drawerCollapsed ? "‹" : "›";
      els.drawerToggleBtn.title = state.drawerCollapsed ? "展开筛选" : "折叠筛选";
      els.drawerToggleBtn.setAttribute("aria-label", els.drawerToggleBtn.title);
    }
    persistFilterDrawerCollapsedPreference(state.drawerCollapsed);
  }

  function changeLogFontSize(delta) {
    const next = clampLogFontSize((state.logFontSizePx || LOG_FONT_SIZE_DEFAULT) + delta);
    applyLogFontSize(next);
    persistLogFontSize(next);
  }

  function resetLogFontSize() {
    applyLogFontSize(LOG_FONT_SIZE_DEFAULT);
    persistLogFontSize(LOG_FONT_SIZE_DEFAULT);
  }

  async function api(path, options = {}) {
    const resp = await fetch(path, {
      headers: { "Content-Type": "application/json" },
      ...options
    });
    if (!resp.ok) {
      const text = await resp.text();
      throw new Error(text || `HTTP ${resp.status}`);
    }
    const ct = resp.headers.get("content-type") || "";
    return ct.includes("application/json") ? await resp.json() : await resp.text();
  }

  function asArray(data) {
    if (Array.isArray(data)) return data;
    if (data && Array.isArray(data.data)) return data.data;
    if (data && Array.isArray(data.items)) return data.items;
    return [];
  }

  function resetSearchNavigation() {
    state.searchCursor = -1;
    updateSearchNavigationUI();
    clearSearchConfirmHint();
  }

  function resetSearchPanel() {
    state.searchHits = [];
    state.searchCollapsedGroups = {};
    state.searchCursor = -1;
    state.lastSearchKeyword = "";
    state.lastSearchScope = "";
    state.pendingAllSearchConfirm = null;
    if (els.searchKeyword) els.searchKeyword.value = "";
    if (els.searchScope) els.searchScope.value = "currentFile";
    if (els.caseSensitive) els.caseSensitive.checked = false;
    if (els.searchSummary) els.searchSummary.textContent = "";
    if (els.searchResults) els.searchResults.innerHTML = `<div class="muted small">未搜索</div>`;
    clearSearchConfirmHint();
    updateSearchNavigationUI();
    updateSearchGroupToolButtons();
    syncStateToActiveTab();
  }

  function showSearchConfirmHint(message) {
    if (!els.searchConfirmHint) return;
    els.searchConfirmHint.textContent = String(message || "");
    els.searchConfirmHint.hidden = !message;
  }

  function clearSearchConfirmHint() {
    if (!els.searchConfirmHint) return;
    els.searchConfirmHint.textContent = "";
    els.searchConfirmHint.hidden = true;
  }

  function loadStarredProjectPaths() {
    try {
      const raw = localStorage.getItem(STARRED_PROJECTS_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      const values = Array.isArray(parsed)
        ? parsed
        : (parsed && typeof parsed === "object"
          ? Object.keys(parsed).filter((k) => parsed[k])
          : []);
      const seen = {};
      const out = [];
      for (const v of values) {
        if (typeof v !== "string" || !v || seen[v]) continue;
        seen[v] = true;
        out.push(v);
      }
      return out;
    } catch (e) {
      console.warn(e);
      return [];
    }
  }

  function syncStarredProjectPathsMap() {
    const order = Array.isArray(state.starredProjectOrder) ? state.starredProjectOrder : [];
    const seen = {};
    const normalizedOrder = [];
    const map = {};
    for (const path of order) {
      if (typeof path !== "string" || !path || seen[path]) continue;
      seen[path] = true;
      normalizedOrder.push(path);
      map[path] = true;
    }
    state.starredProjectOrder = normalizedOrder;
    state.starredProjectPaths = map;
  }

  function persistStarredProjectPaths() {
    try {
      syncStarredProjectPathsMap();
      localStorage.setItem(STARRED_PROJECTS_KEY, JSON.stringify(state.starredProjectOrder || []));
    } catch (e) {
      console.warn(e);
    }
  }

  function isProjectStarred(projectPath) {
    return !!(state.starredProjectPaths && state.starredProjectPaths[projectPath]);
  }

  function toggleProjectStar(projectPath) {
    if (!projectPath) return;
    const next = !isProjectStarred(projectPath);
    if (next) {
      state.starredProjectOrder.push(projectPath);
    } else {
      state.starredProjectOrder = state.starredProjectOrder.filter((p) => p !== projectPath);
    }
    persistStarredProjectPaths();
    renderProjectTree();
  }

  function updateSearchNavigationUI() {
    const total = state.searchHits.length;
    const hasHits = total > 0 && !!state.projectPath;
    els.searchPrevBtn.disabled = !hasHits;
    els.searchNextBtn.disabled = !hasHits;
    if (!hasHits) {
      els.searchNavInfo.textContent = "未搜索";
      return;
    }
    const current = state.searchCursor >= 0 ? state.searchCursor + 1 : 0;
    els.searchNavInfo.textContent = `当前命中 ${current}/${total}`;
  }

  function groupHitsByFile(hits) {
    const groups = [];
    const indexByFile = new Map();
    hits.forEach((hit, idx) => {
      const key = hit.fileName || "(unknown)";
      let group = indexByFile.get(key);
      if (!group) {
        group = { fileName: key, date: hit.date, items: [] };
        indexByFile.set(key, group);
        groups.push(group);
      }
      group.items.push({ hit, idx });
    });
    return groups;
  }

  function searchGroupKey(fileName, date) {
    return `${fileName || ""}@@${date || ""}`;
  }

  function hitGroupKey(hit) {
    return searchGroupKey(hit && hit.fileName, hit && hit.date);
  }

  function ensureSearchGroupState(groups) {
    const next = {};
    for (const group of groups) {
      const key = searchGroupKey(group.fileName, group.date);
      next[key] = !!state.searchCollapsedGroups[key];
    }
    state.searchCollapsedGroups = next;
  }

  function setAllSearchGroupsCollapsed(collapsed) {
    const groups = groupHitsByFile(state.searchHits || []);
    const next = {};
    for (const group of groups) {
      next[searchGroupKey(group.fileName, group.date)] = !!collapsed;
    }
    state.searchCollapsedGroups = next;
    renderSearchResults(state.lastSearchKeyword);
  }

  function updateSearchGroupToolButtons() {
    const groups = groupHitsByFile(state.searchHits || []);
    const disabled = groups.length === 0;
    if (els.searchExpandAllBtn) els.searchExpandAllBtn.disabled = disabled;
    if (els.searchCollapseAllBtn) els.searchCollapseAllBtn.disabled = disabled;
  }

  function generateServerId(nameHint) {
    const base = String(nameHint || "server")
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "") || "server";
    return `${base}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  }

  function normalizeServerFormSeed(server, idx) {
    const keepMasked = !!(server && server.passwordEncrypted === "******");
    return {
      id: (server && server.id) ? server.id : "",
      name: (server && server.name) ? server.name : `server-${idx + 1}`,
      host: (server && server.host) ? server.host : "",
      port: (server && Number(server.port) > 0) ? Number(server.port) : 22,
      username: (server && server.username) ? server.username : "",
      rootPath: (server && server.rootPath) ? server.rootPath : "/home/devops/deploy/backend",
      keepMaskedPassword: keepMasked
    };
  }

  function createServerFormRow(seed, idx) {
    const data = normalizeServerFormSeed(seed, idx);
    const passwordValue = data.keepMaskedPassword ? "******" : "";
    const row = document.createElement("div");
    row.className = "server-form-row";
    row.dataset.serverId = data.id || generateServerId(data.name);
    row.dataset.keepMasked = data.keepMaskedPassword ? "1" : "0";
    row.innerHTML = `
      <div class="server-form-row-head">
        <div class="small muted">服务器 #${idx + 1}</div>
        <div class="server-form-actions">
          <button type="button" class="cfg-remove-btn btn-icon btn-danger" title="删除服务器" aria-label="删除服务器">×</button>
        </div>
      </div>
      <div class="server-form-grid">
        <label class="server-form-field">
          <span>名称</span>
          <input type="text" class="cfg-name" value="${escapeHtml(data.name)}" placeholder="例如：dev-a">
        </label>
        <label class="server-form-field">
          <span>Host</span>
          <input type="text" class="cfg-host" value="${escapeHtml(data.host)}" placeholder="例如：192.168.1.10">
        </label>
        <label class="server-form-field">
          <span>Port</span>
          <input type="number" class="cfg-port" min="1" max="65535" value="${data.port}">
        </label>
        <label class="server-form-field">
          <span>用户名</span>
          <input type="text" class="cfg-username" value="${escapeHtml(data.username)}" placeholder="例如：devops">
        </label>
        <label class="server-form-field full">
          <span>密码</span>
          <input type="password" class="cfg-password" value="${passwordValue}" autocomplete="new-password">
        </label>
        <label class="server-form-field full">
          <span>Root Path</span>
          <input type="text" class="cfg-root-path" value="${escapeHtml(data.rootPath)}" placeholder="/home/devops/deploy/backend">
        </label>
      </div>
    `;
    const removeBtn = row.querySelector(".cfg-remove-btn");
    if (removeBtn) {
      removeBtn.addEventListener("click", () => {
        const siblings = els.serverFormList ? els.serverFormList.querySelectorAll(".server-form-row").length : 0;
        if (siblings <= 1) {
          notify("至少保留一条服务器配置", "warn");
          return;
        }
        row.remove();
        refreshServerFormIndexes();
      });
    }
    return row;
  }

  function refreshServerFormIndexes() {
    if (!els.serverFormList) return;
    const rows = els.serverFormList.querySelectorAll(".server-form-row");
    rows.forEach((row, idx) => {
      const label = row.querySelector(".server-form-row-head .small");
      if (label) {
        label.textContent = `服务器 #${idx + 1}`;
      }
    });
  }

  function renderConfigForm(config) {
    if (!els.serverFormList) return;
    els.serverFormList.innerHTML = "";
    const servers = (config && Array.isArray(config.servers)) ? config.servers : [];
    if (!servers.length) {
      els.serverFormList.appendChild(createServerFormRow({}, 0));
      refreshServerFormIndexes();
      return;
    }
    servers.forEach((server, idx) => {
      els.serverFormList.appendChild(createServerFormRow(server, idx));
    });
    refreshServerFormIndexes();
  }

  function collectConfigFormPayload() {
    if (!els.serverFormList) {
      throw new Error("配置表单不可用");
    }
    const rows = Array.from(els.serverFormList.querySelectorAll(".server-form-row"));
    const servers = [];
    const seenIds = {};
    rows.forEach((row, idx) => {
      const name = (row.querySelector(".cfg-name")?.value || "").trim();
      const host = (row.querySelector(".cfg-host")?.value || "").trim();
      const portRaw = (row.querySelector(".cfg-port")?.value || "").trim();
      const username = (row.querySelector(".cfg-username")?.value || "").trim();
      const passwordInput = (row.querySelector(".cfg-password")?.value || "").trim();
      const rootPath = (row.querySelector(".cfg-root-path")?.value || "").trim();
      const keepMasked = row.dataset.keepMasked === "1";
      const emptyRow = !name && !host && !portRaw && !username && !passwordInput && !rootPath;
      if (emptyRow) {
        return;
      }
      if (!name || !host || !username || !rootPath) {
        throw new Error(`服务器 #${idx + 1} 必填项缺失（name/host/username/rootPath）`);
      }
      const port = Number(portRaw || 22);
      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error(`服务器 #${idx + 1} 端口无效`);
      }
      let serverId = (row.dataset.serverId || "").trim();
      if (!serverId || seenIds[serverId]) {
        serverId = generateServerId(name);
        row.dataset.serverId = serverId;
      }
      seenIds[serverId] = true;
      let passwordEncrypted = passwordInput;
      if (keepMasked && (!passwordEncrypted || passwordEncrypted === "******")) {
        passwordEncrypted = "******";
      }
      if (!passwordEncrypted) {
        throw new Error(`服务器 #${idx + 1} 密码不能为空`);
      }
      servers.push({
        id: serverId,
        name,
        host,
        port,
        username,
        passwordEncrypted,
        rootPath
      });
    });
    if (!servers.length) {
      throw new Error("至少需要一条服务器配置");
    }
    return {
      version: 1,
      defaultServerId: servers[0].id,
      servers
    };
  }

  async function loadBootstrap() {
    state.bootstrap = await api("/api/bootstrap");
    renderServerSelect();
    renderConfigForm(state.bootstrap);
  }

  function renderServerSelect() {
    const bootstrapServers = (state.bootstrap && state.bootstrap.servers) ? state.bootstrap.servers : [];
    const servers = bootstrapServers.slice();
    els.serverSelect.innerHTML = "";
    for (const s of servers) {
      const option = document.createElement("option");
      option.value = s.id;
      option.textContent = `${s.name || s.id} (${s.host})`;
      els.serverSelect.appendChild(option);
    }
    state.serverId = (state.bootstrap && state.bootstrap.defaultServerId) || (servers[0] ? servers[0].id : null) || null;
    if (state.serverId) {
      els.serverSelect.value = state.serverId;
      updateServerMeta();
    }
    syncStateToActiveTab();
    renderLogTabs();
    updateActionAvailability();
  }

  function updateServerMeta() {
    const bootstrapServers = (state.bootstrap && state.bootstrap.servers) ? state.bootstrap.servers : [];
    const server = bootstrapServers.find(s => s.id === state.serverId);
    if (!server) {
      els.serverMeta.textContent = "";
      return;
    }
    els.serverMeta.textContent = `${server.username}@${server.host}:${server.port} | Root Path = ${server.rootPath}`;
  }

  async function loadProjects() {
    if (!state.serverId) return;
    setStatus("加载项目中...");
    const resp = await api(`/api/projects?serverId=${encodeURIComponent(state.serverId)}`);
    state.projects = asArray(resp);
    renderL1FilterOptions();
    renderProjectTree();
    setStatus(`项目已加载（${state.projects.length}）`);
    updateActionAvailability();
  }

  function renderL1FilterOptions() {
    if (!els.l1Filter) return;
    const current = els.l1Filter.value || "";
    const names = [];
    for (const p of state.projects) {
      if (p && p.l1Name && names.indexOf(p.l1Name) < 0) {
        names.push(p.l1Name);
      }
    }
    names.sort((a, b) => a.localeCompare(b));
    els.l1Filter.innerHTML = "";
    const allOpt = document.createElement("option");
    allOpt.value = "";
    allOpt.textContent = "全部一级目录";
    els.l1Filter.appendChild(allOpt);
    for (const name of names) {
      const opt = document.createElement("option");
      opt.value = name;
      opt.textContent = name;
      els.l1Filter.appendChild(opt);
    }
    let nextValue = "";
    if (names.indexOf(current) >= 0) {
      nextValue = current;
    }
    els.l1Filter.value = nextValue;
    state.l1FilterInitialized = true;
  }

  function renderProjectTree() {
    const filter = els.projectFilter.value.trim().toLowerCase();
    const l1Filter = (els.l1Filter && els.l1Filter.value) ? els.l1Filter.value : "";
    const starred = [];
    const groups = new Map();
    for (const p of state.projects) {
      if (isProjectStarred(p.projectPath)) {
        starred.push(p);
        continue;
      }
      if (l1Filter && p.l1Name !== l1Filter) continue;
      if (filter && !p.projectName.toLowerCase().includes(filter)) continue;
      if (!groups.has(p.l1Name)) groups.set(p.l1Name, []);
      groups.get(p.l1Name).push(p);
    }
    els.projectTree.innerHTML = "";
    if (groups.size === 0 && starred.length === 0) {
      els.projectTree.innerHTML = `<div class="muted small">没有匹配项目。请检查 rootPath 是否为 /home/devops/deploy/backend、账号是否有读取权限、以及目录是否是两层结构。</div>`;
      return;
    }
    if (starred.length > 0) {
      appendProjectGroup("★ 星标项目", orderStarredProjects(starred), true);
    }
    for (const [l1, items] of [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))) {
      appendProjectGroup(l1, items.sort(compareProjects), false);
    }

    function appendProjectGroup(title, items, starredGroup) {
      const box = document.createElement("div");
      box.className = "tree-group" + (starredGroup ? " tree-group-starred" : "");
      box.innerHTML = `<div class="tree-group-title">${escapeHtml(title)}</div>`;
      const list = document.createElement("div");
      list.className = "tree-items";
      items.forEach((p) => list.appendChild(createProjectItem(p, { starredGroup })));
      box.appendChild(list);
      els.projectTree.appendChild(box);
    }
  }

  function compareProjects(a, b) {
    const l1 = (a.l1Name || "").localeCompare(b.l1Name || "");
    if (l1 !== 0) return l1;
    return (a.projectName || "").localeCompare(b.projectName || "");
  }

  function orderStarredProjects(items) {
    const indexMap = new Map();
    (state.starredProjectOrder || []).forEach((path, idx) => {
      if (!indexMap.has(path)) {
        indexMap.set(path, idx);
      }
    });
    return (items || []).slice().sort((a, b) => {
      const ai = indexMap.has(a.projectPath) ? indexMap.get(a.projectPath) : Number.MAX_SAFE_INTEGER;
      const bi = indexMap.has(b.projectPath) ? indexMap.get(b.projectPath) : Number.MAX_SAFE_INTEGER;
      if (ai !== bi) return ai - bi;
      return compareProjects(a, b);
    });
  }

  function getFirstStarredProjectForCurrentServer() {
    if (!Array.isArray(state.projects) || !state.projects.length) return null;
    for (const path of state.starredProjectOrder || []) {
      const p = state.projects.find((x) => x && x.projectPath === path);
      if (p) return p;
    }
    const fallback = state.projects.filter((p) => isProjectStarred(p.projectPath)).sort(compareProjects);
    return fallback[0] || null;
  }

  function reorderStarredProject(sourcePath, targetPath, placeAfter) {
    if (!sourcePath || !targetPath || sourcePath === targetPath) return;
    const order = Array.isArray(state.starredProjectOrder) ? state.starredProjectOrder.slice() : [];
    const sourceIndex = order.indexOf(sourcePath);
    const targetIndexRaw = order.indexOf(targetPath);
    if (sourceIndex < 0 || targetIndexRaw < 0) return;
    const [moved] = order.splice(sourceIndex, 1);
    const targetIndex = order.indexOf(targetPath);
    const insertAt = placeAfter ? targetIndex + 1 : targetIndex;
    order.splice(Math.max(0, Math.min(order.length, insertAt)), 0, moved);
    state.starredProjectOrder = order;
    persistStarredProjectPaths();
    renderProjectTree();
  }

  async function openDefaultStarredProjectIfNeeded() {
    if (state.startupDefaultProjectDone) return;
    state.startupDefaultProjectDone = true;
    const active = getActiveTab();
    if (active && active.projectPath) return;
    const first = getFirstStarredProjectForCurrentServer();
    if (!first) return;
    await openProjectInTab(first, "normal");
  }

  function createProjectItem(p, options = {}) {
    const starredGroup = !!(options && options.starredGroup);
    const btn = document.createElement("div");
    btn.setAttribute("role", "button");
    btn.tabIndex = 0;
    if (starredGroup) {
      btn.title = "点击查看业务日志；可拖拽调整星标顺序";
      btn.draggable = true;
      btn.dataset.projectPath = p.projectPath || "";
    }
    const starred = isProjectStarred(p.projectPath);
    const activeTab = getActiveTab();
    const isActiveProject = !!(activeTab && activeTab.projectPath === p.projectPath);
    const isErrorActive = !!(isActiveProject && activeTab && activeTab.logType === "error");
    const isNormalActive = !!(isActiveProject && !isErrorActive);
    btn.className = "project-item" + (isActiveProject ? " active" : "") + (isErrorActive ? " active-error" : "") + (isNormalActive ? " active-normal" : "") + (starred ? " starred" : "");
    if (starredGroup) {
      btn.className += " project-item-starred";
    }
    const dragHandle = starredGroup ? '<span class="project-drag-handle" title="拖动排序">⋮⋮</span>' : "";
    const noLogsTip = p.hasLogs ? "" : '<span class="project-no-logs" title="该项目无 logs 目录">无日志</span>';
    btn.innerHTML = `
      <span class="project-item-main">
        <span class="project-star${starred ? " on" : ""}" title="${starred ? "取消星标" : "设为星标"}" aria-label="${starred ? "取消星标" : "设为星标"}">${starred ? "★" : "☆"}</span>
        ${dragHandle}
        <span class="project-name">${escapeHtml(p.projectName)}</span>
      </span>
      <span class="project-item-actions">
        ${noLogsTip}
      </span>
    `;
    const clearDropMarkers = () => {
      btn.classList.remove("drop-before", "drop-after");
    };
    if (starredGroup) {
      btn.addEventListener("dragstart", (e) => {
        state.draggingStarProjectPath = p.projectPath;
        btn.classList.add("dragging");
        if (e.dataTransfer) {
          e.dataTransfer.effectAllowed = "move";
          try {
            e.dataTransfer.setData("text/plain", p.projectPath || "");
          } catch (err) {
            console.warn(err);
          }
        }
      });
      btn.addEventListener("dragover", (e) => {
        const sourcePath = state.draggingStarProjectPath;
        if (!sourcePath || sourcePath === p.projectPath) return;
        e.preventDefault();
        const rect = btn.getBoundingClientRect();
        const before = (e.clientY - rect.top) < (rect.height / 2);
        btn.classList.toggle("drop-before", before);
        btn.classList.toggle("drop-after", !before);
      });
      btn.addEventListener("dragleave", () => {
        clearDropMarkers();
      });
      btn.addEventListener("drop", (e) => {
        e.preventDefault();
        const sourcePath = state.draggingStarProjectPath
          || (e.dataTransfer ? e.dataTransfer.getData("text/plain") : "");
        clearDropMarkers();
        if (!sourcePath || sourcePath === p.projectPath) return;
        const rect = btn.getBoundingClientRect();
        const placeAfter = (e.clientY - rect.top) >= (rect.height / 2);
        reorderStarredProject(sourcePath, p.projectPath, placeAfter);
        state.suppressNextProjectClick = true;
        setTimeout(() => {
          state.suppressNextProjectClick = false;
        }, 0);
      });
      btn.addEventListener("dragend", () => {
        state.draggingStarProjectPath = null;
        btn.classList.remove("dragging");
        clearDropMarkers();
      });
    }
    btn.addEventListener("click", async (e) => {
      if (state.suppressNextProjectClick) {
        state.suppressNextProjectClick = false;
        return;
      }
      const star = e.target && e.target.closest ? e.target.closest(".project-star") : null;
      if (star) {
        e.preventDefault();
        e.stopPropagation();
        toggleProjectStar(p.projectPath);
        return;
      }
      if (!p.hasLogs) {
        notify("该项目没有 logs 目录", "warn", 2200);
        return;
      }
      await openProjectInTab(p, "normal");
    });
    btn.addEventListener("keydown", async (e) => {
      const inAction = e.target && e.target.closest && e.target.closest(".project-star");
      if (inAction) return;
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        if (!p.hasLogs) {
          notify("该项目没有 logs 目录", "warn", 2200);
          return;
        }
        await openProjectInTab(p, "normal");
      }
    });
    return btn;
  }

  async function loadFiles(options = {}) {
    if (!state.serverId || !state.projectPath) return;
    const opts = options || {};
    const silent = !!opts.silent;
    const preferredFile = opts.preferredFile || null;
    if (!silent) {
      setStatus("加载日志文件...");
    }
    const qs = new URLSearchParams({
      serverId: state.serverId,
      projectPath: state.projectPath,
      type: state.logType
    });
    const resp = await api(`/api/logs/files?${qs.toString()}`);
    state.files = asArray(resp);
    state.lastFilesRefreshAtEpochMs = Date.now();
    if (preferredFile) {
      const matched = state.files.find((f) => f.fileName === preferredFile);
      if (matched) {
        state.currentFile = matched.fileName;
        state.currentFileMeta = matched;
      }
    }
    renderFiles();
    if (!silent) {
      setStatus(`文件已加载（${state.files.length}）`);
    }
    syncStateToActiveTab();
  }

  async function autoRefreshFilesFromSelect() {
    if (!state.serverId || !state.projectPath || !els.fileSelect) return;
    const now = Date.now();
    const cooldownMs = 4000;
    if (now - (state.lastFilesRefreshAtEpochMs || 0) < cooldownMs) return;
    const selectedFile = els.fileSelect.value || state.currentFile || "";
    await loadFiles({ silent: true, preferredFile: selectedFile });
  }

  function renderFiles() {
    els.fileSelect.innerHTML = "";
    const keyword = (els.fileFilter ? els.fileFilter.value : "").trim().toLowerCase();
    const visibleFiles = !keyword
      ? state.files.slice()
      : state.files.filter((f) => (f.fileName || "").toLowerCase().includes(keyword));
    if (!state.files.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "暂无匹配日志文件";
      els.fileSelect.appendChild(opt);
      els.fileMeta.textContent = "检查 logs 目录和命名规则：app.yyyymmdd.index.log / app.error.yyyymmdd.index.log";
      syncStateToActiveTab();
      updateActionAvailability();
      return;
    }
    if (!visibleFiles.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "过滤后无匹配文件";
      els.fileSelect.appendChild(opt);
      els.fileMeta.textContent = `当前过滤：${keyword}`;
      syncStateToActiveTab();
      updateActionAvailability();
      return;
    }
    const preferred = state.currentFile || (state.currentFileMeta && state.currentFileMeta.fileName) || null;
    for (const f of visibleFiles) {
      const opt = document.createElement("option");
      opt.value = f.fileName;
      opt.textContent = `${f.fileName} [${formatBytes(f.size)}]`;
      els.fileSelect.appendChild(opt);
    }
    const selected = visibleFiles.find((f) => f.fileName === preferred) || visibleFiles[0];
    els.fileSelect.value = selected.fileName;
    state.currentFile = selected.fileName;
    state.currentFileMeta = selected;
    updateFileMeta();
    syncStateToActiveTab();
    updateActionAvailability();
  }

  function updateFileMeta() {
    const f = state.files.find(x => x.fileName === els.fileSelect.value);
    if (!f) {
      els.fileMeta.textContent = "";
      syncStateToActiveTab();
      updateActionAvailability();
      return;
    }
    state.currentFileMeta = f;
    state.currentFile = f.fileName;
    els.fileMeta.textContent = `date=${f.date} index=${f.index} size=${formatBytes(f.size)} mtime=${new Date(f.mtimeEpochMs).toLocaleString()}`;
    syncStateToActiveTab();
    updateActionAvailability();
  }

  function ensureWebSocket(tab = getActiveTab()) {
    if (!tab) return;
    if (tab.ws && (tab.ws.readyState === WebSocket.OPEN || tab.ws.readyState === WebSocket.CONNECTING)) {
      if (tab.id === state.activeTabId) {
        state.ws = tab.ws;
        state.wsConnected = !!tab.wsConnected;
      }
      return;
    }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${proto}://${location.host}/ws/logs`);
    tab.ws = ws;
    if (tab.id === state.activeTabId) {
      state.ws = ws;
    }
    ws.onopen = () => {
      tab.wsConnected = true;
      tab.pausedByIdle = false;
      if (tab.id === state.activeTabId) {
        state.wsConnected = true;
      }
      setTabStatus(tab, "WebSocket 已连接");
    };
    ws.onclose = () => {
      tab.wsConnected = false;
      if (tab.id === state.activeTabId) {
        state.wsConnected = false;
      }
      if (!tab.pausedByIdle) {
        setTabStatus(tab, "WebSocket 已断开", "error");
      } else {
        renderLogTabs();
      }
    };
    ws.onerror = () => {
      setTabStatus(tab, "WebSocket 错误", "error");
    };
    ws.onmessage = (ev) => handleWsMessage(ev.data, tab);
  }

  function wsSend(payload, tab = getActiveTab()) {
    if (!tab) return;
    ensureWebSocket(tab);
    const ws = tab.ws;
    if (!ws) return;
    const sendNow = () => ws.send(JSON.stringify(payload));
    if (ws.readyState === WebSocket.OPEN) {
      sendNow();
    } else {
      const retry = setInterval(() => {
        if (!tab.ws) {
          clearInterval(retry);
          return;
        }
        if (tab.ws.readyState === WebSocket.OPEN) {
          clearInterval(retry);
          sendNow();
        }
        if (tab.ws.readyState === WebSocket.CLOSED) {
          clearInterval(retry);
        }
      }, 200);
      setTimeout(() => clearInterval(retry), 5000);
    }
  }

  function handleWsMessage(raw, tab = getActiveTab()) {
    if (!tab) return;
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }
    if (msg.type === "status") {
      const label = {
        connected: "已连接",
        connecting: "连接中",
        following: "实时追踪中",
        reconnecting: "重连中"
      }[msg.status] || msg.status;
      if (msg.status === "following") {
        tab.realtimeWanted = true;
        tab.pausedByIdle = false;
      }
      if (msg.fileName) {
        tab.currentFile = msg.fileName;
      }
      setTabStatus(tab, label + (msg.fileName ? ` (${msg.fileName})` : ""));
      if (tab.id === state.activeTabId) {
        state.currentFile = tab.currentFile || state.currentFile;
        if (msg.fileName) els.currentFileLabel.textContent = msg.fileName;
      }
      return;
    }
    if (msg.type === "file_switched") {
      appendTextToTabBuffer(tab, `\n[system] 已切换文件: ${msg.fromFile} -> ${msg.toFile}\n`);
      tab.currentFile = msg.toFile || tab.currentFile;
      tab.lastRealtimeMode = "latest";
      if (tab.id === state.activeTabId) {
        state.currentFile = tab.currentFile;
        els.currentFileLabel.textContent = tab.currentFile || "-";
        state.logBuffer = tab.logBuffer || "";
        renderLogBuffer();
        if (state.autoFollow) {
          scrollToBottom();
        } else {
          els.backToBottomBtn.hidden = false;
        }
        // Refresh file list so latest marker/selection keeps up.
        loadFiles().catch(console.error);
      } else {
        renderLogTabs();
      }
      return;
    }
    if (msg.type === "log_lines") {
      if (msg.fileName) {
        tab.currentFile = msg.fileName;
      }
      const text = (msg.lines || []).join("\n") + ((msg.lines || []).length ? "\n" : "");
      appendTextToTabBuffer(tab, text);
      if (tab.id === state.activeTabId) {
        state.currentFile = tab.currentFile || state.currentFile;
        if (msg.fileName) els.currentFileLabel.textContent = msg.fileName;
        state.logBuffer = tab.logBuffer || "";
        renderLogBuffer();
        if (state.autoFollow) {
          scrollToBottom();
        } else {
          els.backToBottomBtn.hidden = false;
        }
        syncStateToActiveTab();
      } else {
        renderLogTabs();
      }
      return;
    }
  }

  async function openLatestRealtime() {
    const tab = getActiveTab();
    if (!state.projectPath) {
      notify("请先选择项目", "warn");
      return;
    }
    if (!state.files.length) {
      await loadFiles();
      if (!state.files.length) {
        notify("当前日志类型没有匹配文件", "warn");
        return;
      }
    }
    const latest = state.files[0];
    els.fileSelect.value = latest.fileName;
    updateFileMeta();
    if (tab) {
      tab.lastRealtimeMode = "latest";
      tab.lastRealtimeFile = latest.fileName;
      tab.realtimeWanted = true;
      tab.pausedByIdle = false;
    }
    await openFile(latest.fileName, true);
  }

  async function openSelectedFile() {
    if (!state.projectPath) {
      notify("请先选择项目", "warn");
      return;
    }
    const file = els.fileSelect.value;
    if (!file) return;
    await openFile(file, false);
  }

  function resolveCurrentProjectRef() {
    const projectPath = state.projectPath;
    if (!projectPath) return null;
    const matched = (state.projects || []).find((p) => p && p.projectPath === projectPath);
    if (matched) return matched;
    const fallbackName = projectPath.split("/").pop() || projectPath;
    return {
      projectPath,
      projectName: fallbackName,
      l1Name: "",
      hasLogs: true
    };
  }

  async function switchProjectLogTypeTab(targetLogType) {
    const logType = targetLogType === "error" ? "error" : "normal";
    if (state.logType === logType) return;
    const project = resolveCurrentProjectRef();
    if (!project) {
      notify("请先选择项目", "warn");
      return;
    }
    await openProjectInTab(project, logType);
  }

  async function openFile(fileName, latestMode) {
    const tab = getActiveTab();
    const previousScrollLeft = els.logViewer ? els.logViewer.scrollLeft : 0;
    state.focusLineText = "";
    state.viewerHighlightKeyword = "";
    state.viewerHighlightCaseSensitive = false;
    state.currentFile = fileName;
    els.currentFileLabel.textContent = fileName;
    const qs = new URLSearchParams({
      serverId: state.serverId,
      projectPath: state.projectPath,
      file: fileName,
      tailBytes: "65536"
    });
    const chunk = await api(`/api/logs/content/tail?${qs.toString()}`);
    state.fileSize = (chunk && chunk.fileSize) || 0;
    state.startOffset = (chunk && chunk.startOffset) || 0;
    state.endOffset = (chunk && chunk.endOffset) || 0;
    const tailText = (chunk && typeof chunk.text === "string") ? chunk.text : "";
    setLogText(tailText);
    if (!tailText) {
      appendLogText("[system] 当前文件暂无可读内容（可能为空文件，或账号无读取权限）\n", true);
    }
    scrollToBottom();
    if (els.logViewer) {
      els.logViewer.scrollLeft = previousScrollLeft;
    }
    if (tab) {
      tab.realtimeWanted = true;
      tab.pausedByIdle = false;
      tab.lastRealtimeMode = latestMode ? "latest" : "file";
      tab.lastRealtimeFile = latestMode ? fileName : fileName;
      tab.title = buildTabTitle(tab);
    }
    wsSend({
      type: "open_stream",
      serverId: state.serverId,
      projectPath: state.projectPath,
      logType: state.logType,
      mode: latestMode ? "latest" : "file",
      file: latestMode ? undefined : fileName,
      tailLines: 0
    }, tab);
    setTabStatus(tab || getActiveTab(), "打开实时流中...");
    syncStateToActiveTab();
  }

  async function loadPrevChunk() {
    if (!state.projectPath || !state.currentFile || state.loadingPrev) return;
    if (state.startOffset <= 0) return;
    state.loadingPrev = true;
    try {
      const prevHeight = els.logViewer.scrollHeight;
      const prevTop = els.logViewer.scrollTop;
      const qs = new URLSearchParams({
        serverId: state.serverId,
        projectPath: state.projectPath,
        file: state.currentFile,
        beforeOffset: String(state.startOffset),
        maxBytes: "65536"
      });
      const chunk = await api(`/api/logs/content/prev?${qs.toString()}`);
      state.fileSize = chunk.fileSize || state.fileSize;
      state.startOffset = chunk.startOffset || 0;
      prependLogText(chunk.text || "");
      const nextHeight = els.logViewer.scrollHeight;
      els.logViewer.scrollTop = prevTop + (nextHeight - prevHeight);
    } catch (e) {
      console.error(e);
    } finally {
      state.loadingPrev = false;
      syncStateToActiveTab();
    }
  }

  async function searchLogs() {
    if (!state.projectPath) {
      notify("请先选择项目", "warn");
      return;
    }
    if (!state.currentFileMeta && state.files.length) state.currentFileMeta = state.files[0];
    const keyword = els.searchKeyword.value.trim();
    if (!keyword) {
      notify("请输入关键字", "warn");
      return;
    }
    const scope = els.searchScope.value;
    if (scope === "all") {
      const now = Date.now();
      const pending = state.pendingAllSearchConfirm;
      const confirmed = pending
        && pending.projectPath === state.projectPath
        && pending.keyword === keyword
        && pending.logType === state.logType
        && now - pending.ts < 8000;
      if (!confirmed) {
        state.pendingAllSearchConfirm = {
          projectPath: state.projectPath,
          keyword,
          logType: state.logType,
          ts: now
        };
        showSearchConfirmHint("全部日志搜索会增加读取压力，请再次点击搜索图标确认");
        return;
      }
      state.pendingAllSearchConfirm = null;
      clearSearchConfirmHint();
    } else {
      state.pendingAllSearchConfirm = null;
      clearSearchConfirmHint();
    }
    const file = els.fileSelect.value || state.currentFile;
    const fileMeta = state.files.find(f => f.fileName === file) || state.currentFileMeta;
    const body = {
      serverId: state.serverId,
      projectPath: state.projectPath,
      logType: state.logType,
      keyword,
      scope,
      date: fileMeta ? fileMeta.date : undefined,
      file,
      caseSensitive: els.caseSensitive.checked
    };
    state.lastSearchKeyword = keyword;
    state.lastSearchScope = scope;
    resetSearchNavigation();
    els.searchSummary.textContent = "搜索中...";
    const resp = await api("/api/logs/search", {
      method: "POST",
      body: JSON.stringify(body)
    });
    state.searchHits = resp.hits || [];
    state.searchCollapsedGroups = {};
    els.searchSummary.textContent = `命中 ${state.searchHits.length} 条 | 扫描文件 ${resp.scannedFiles} | 扫描字节 ${formatBytes(resp.scannedBytes || 0)}${resp.partial ? " | 结果已截断" : ""}`;
    renderSearchResults(keyword);
    updateSearchGroupToolButtons();
    updateSearchNavigationUI();
    syncStateToActiveTab();
    if (scope === "currentFile" && state.searchHits.length > 0) {
      await openSearchHit(0);
    }
  }

  function renderSearchResults(keyword) {
    els.searchResults.innerHTML = "";
    if (!state.searchHits.length) {
      els.searchResults.innerHTML = `<div class="muted small">没有匹配结果</div>`;
      updateSearchGroupToolButtons();
      syncStateToActiveTab();
      return;
    }
    const groups = groupHitsByFile(state.searchHits);
    ensureSearchGroupState(groups);
    for (const group of groups) {
      const key = searchGroupKey(group.fileName, group.date);
      const collapsed = !!state.searchCollapsedGroups[key];
      const groupBox = document.createElement("div");
      groupBox.className = "search-group" + (collapsed ? " collapsed" : "");
      groupBox.dataset.groupKey = key;
      const head = document.createElement("button");
      head.type = "button";
      head.className = "search-group-head";
      head.innerHTML = `
        <span class="search-group-arrow">${collapsed ? "▸" : "▾"}</span>
        <span class="search-group-title-text">${escapeHtml(group.fileName)}${group.date ? `  [${escapeHtml(group.date)}]` : ""}  (${group.items.length})</span>
      `;
      head.addEventListener("click", () => {
        state.searchCollapsedGroups[key] = !state.searchCollapsedGroups[key];
        renderSearchResults(state.lastSearchKeyword);
      });
      const body = document.createElement("div");
      body.className = "search-group-body";
      body.hidden = collapsed;
      for (const entry of group.items) {
        const hit = entry.hit;
        const idx = entry.idx;
        const item = document.createElement("div");
        item.className = "search-item" + (state.searchCursor === idx ? " active" : "");
        const title = document.createElement("div");
        title.className = "small muted";
        title.textContent = `line ${hit.lineNumber == null ? "-" : hit.lineNumber}  offset ${hit.offset == null ? "-" : hit.offset}`;
        const line = document.createElement("div");
        line.className = "line";
        line.innerHTML = highlightText(hit.lineText || "", keyword, els.caseSensitive.checked);
        item.append(title, line);
        item.addEventListener("click", () => openSearchHit(idx).catch(showError));
        body.appendChild(item);
      }
      groupBox.append(head, body);
      els.searchResults.appendChild(groupBox);
    }
    updateSearchGroupToolButtons();
    syncStateToActiveTab();
  }

  async function openSearchHit(index) {
    if (index < 0 || index >= state.searchHits.length) return;
    const hit = state.searchHits[index];
    state.searchCollapsedGroups[hitGroupKey(hit)] = false;
    state.searchCursor = index;
    updateSearchNavigationUI();
    renderSearchResults(state.lastSearchKeyword);
    requestAnimationFrame(() => {
      const active = els.searchResults.querySelector(".search-item.active");
      if (active && typeof active.scrollIntoView === "function") {
        active.scrollIntoView({ block: "nearest" });
      }
    });
    if (hit.fileName) {
      els.fileSelect.value = hit.fileName;
      updateFileMeta();
    }
    state.focusLineText = hit.lineText || "";
    state.viewerHighlightKeyword = state.lastSearchKeyword || "";
    state.viewerHighlightCaseSensitive = !!els.caseSensitive.checked;
    if (hit.offset != null && !Number.isNaN(Number(hit.offset))) {
      await openFileAtOffset(hit.fileName, Number(hit.offset), hit.lineNumber);
    } else {
      await openFile(hit.fileName, false);
      state.focusLineText = hit.lineText || "";
      state.viewerHighlightKeyword = state.lastSearchKeyword || "";
      state.viewerHighlightCaseSensitive = !!els.caseSensitive.checked;
      renderLogBuffer();
      scrollFocusedLogLineIntoView();
    }
    syncStateToActiveTab();
  }

  async function moveSearchCursor(delta) {
    if (!state.searchHits.length) return;
    let next;
    if (state.searchCursor < 0) {
      next = delta >= 0 ? 0 : state.searchHits.length - 1;
    } else {
      next = (state.searchCursor + delta + state.searchHits.length) % state.searchHits.length;
    }
    await openSearchHit(next);
  }

  async function openFileAtOffset(fileName, offset, lineNumber) {
    if (!state.projectPath || !fileName) return;
    closeRealtimeStream(true);
    clearViewer();
    state.currentFile = fileName;
    els.currentFileLabel.textContent = fileName;
    const fromOffset = Math.max(0, Math.floor(Number(offset || 0)) - 32768);
    const qs = new URLSearchParams({
      serverId: state.serverId,
      projectPath: state.projectPath,
      file: fileName,
      fromOffset: String(fromOffset),
      maxBytes: "65536"
    });
    const chunk = await api(`/api/logs/content/chunk?${qs.toString()}`);
    state.fileSize = (chunk && chunk.fileSize) || 0;
    state.startOffset = (chunk && chunk.startOffset) || fromOffset;
    state.endOffset = (chunk && chunk.endOffset) || fromOffset;
    setLogText((chunk && chunk.text) || "");
    jumpViewerToOffsetApprox(Number(offset || 0));
    scrollFocusedLogLineIntoView();
    setStatus(`已定位搜索结果${lineNumber ? ` (line ${lineNumber})` : ""}`);
    notify("已进入历史定位视图，可点击“恢复实时”回到实时追踪", "info", 2800);
    updateModeUI();
    syncStateToActiveTab();
  }

  function jumpViewerToOffsetApprox(targetOffset) {
    const start = Number(state.startOffset || 0);
    const end = Number(state.endOffset || start);
    const total = Math.max(1, end - start);
    const ratio = Math.max(0, Math.min(1, (targetOffset - start) / total));
    const targetTop = Math.max(0, ratio * els.logViewer.scrollHeight - els.logViewer.clientHeight * 0.35);
    els.logViewer.scrollTop = targetTop;
    state.autoFollow = false;
    els.backToBottomBtn.hidden = false;
  }

  function scrollFocusedLogLineIntoView() {
    requestAnimationFrame(() => {
      const focused = els.logContent.querySelector(".log-line-focus");
      if (!focused) return;
      const top = Math.max(0, focused.offsetTop - els.logViewer.clientHeight * 0.35);
      els.logViewer.scrollTop = top;
      state.autoFollow = false;
      els.backToBottomBtn.hidden = false;
    });
  }

  function closeRealtimeStream(markDesiredOff = false, tab = getActiveTab()) {
    if (!tab) return;
    shutdownTabSocket(tab, !!markDesiredOff);
    if (tab.id === state.activeTabId) {
      applyTabStatusToUI(tab);
    }
  }

  function renderLogBuffer() {
    els.logContent.innerHTML = formatLogBufferHtml(state.logBuffer);
  }

  function formatLogBufferHtml(text) {
    if (!text) return "";
    const lines = text.split("\n");
    let focusMarked = false;
    return lines.map((line) => {
      const classes = ["log-line"];
      const lower = line.toLowerCase();
      if (line.indexOf("[system]") === 0) classes.push("log-line-system");
      if (/\bFATAL\b/.test(line)) classes.push("log-line-fatal");
      else if (/\bERROR\b/.test(line)) classes.push("log-line-error");
      else if (/\bWARN\b/.test(line)) classes.push("log-line-warn");
      else if (/\bINFO\b/.test(line)) classes.push("log-line-info");
      else if (/\bDEBUG\b/.test(line)) classes.push("log-line-debug");
      else if (/\bTRACE\b/.test(line)) classes.push("log-line-trace");
      if (/^\s+at\s+/.test(line) || /^\s*Caused by:/.test(line)) classes.push("log-line-stack");
      if (lower.indexOf("exception") >= 0 || lower.indexOf("error") >= 0) classes.push("log-line-ex");
      if (!focusMarked && state.focusLineText && line === state.focusLineText) {
        classes.push("log-line-focus");
        focusMarked = true;
      }
      return `<div class="${classes.join(" ")}">${formatLogLineHtml(line)}</div>`;
    }).join("");
  }

  function formatLogLineHtml(line) {
    const withTokens = applyViewerKeywordTokens(line || "");
    let html = escapeHtml(withTokens);
    html = html.replace(
      /(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d{3})?)/,
      '<span class="log-ts">$1</span>'
    );
    html = html.replace(
      /\b(FATAL|ERROR|WARN|INFO|DEBUG|TRACE)\b/g,
      (m) => `<span class="log-lv-${m.toLowerCase()}">${m}</span>`
    );
    html = html.replace(/(\[[^\]]+\])/g, '<span class="log-thread">$1</span>');
    html = html.replace(
      /([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:Exception|Error))(?![\w$])/g,
      '<span class="log-ex">$1</span>'
    );
    html = html.replace(
      /\b([a-z][\w$]*(?:\.[a-zA-Z_$][\w$]*){2,})\b/g,
      '<span class="log-pkg">$1</span>'
    );
    html = html.replace(/&lt;&lt;#1#&gt;&gt;/g, '<mark class="viewer-hit">');
    html = html.replace(/&lt;&lt;#2#&gt;&gt;/g, "</mark>");
    return html;
  }

  function applyViewerKeywordTokens(text) {
    const keyword = state.viewerHighlightKeyword || "";
    if (!keyword) return text;
    try {
      const flags = state.viewerHighlightCaseSensitive ? "g" : "gi";
      const re = new RegExp(escapeRegExp(keyword), flags);
      return String(text).replace(re, (m) => `<<#1#>>${m}<<#2#>>`);
    } catch (e) {
      console.warn(e);
      return text;
    }
  }

  function clearViewer() {
    state.logBuffer = "";
    renderLogBuffer();
    state.startOffset = 0;
    state.endOffset = 0;
    state.fileSize = 0;
    syncStateToActiveTab();
  }

  function setLogText(text) {
    state.logBuffer = text || "";
    trimViewerIfNeeded();
    renderLogBuffer();
    syncStateToActiveTab();
  }

  function appendLogText(text, systemLine) {
    if (!text) return;
    const suffix = systemLine ? text : text;
    state.logBuffer += suffix;
    trimViewerIfNeeded();
    renderLogBuffer();
    if (state.autoFollow) {
      scrollToBottom();
    } else {
      els.backToBottomBtn.hidden = false;
    }
    syncStateToActiveTab();
  }

  function prependLogText(text) {
    if (!text) return;
    state.logBuffer = text + state.logBuffer;
    trimViewerIfNeeded(false);
    renderLogBuffer();
    syncStateToActiveTab();
  }

  function trimViewerIfNeeded(trimHead = true) {
    const maxChars = 1000000;
    const value = state.logBuffer || "";
    if (value.length <= maxChars) return;
    if (trimHead) {
      state.logBuffer = value.slice(value.length - maxChars);
    } else {
      state.logBuffer = value.slice(0, maxChars);
    }
    syncStateToActiveTab();
  }

  function scrollToBottom() {
    els.logViewer.scrollTop = els.logViewer.scrollHeight;
    state.autoFollow = true;
    els.backToBottomBtn.hidden = true;
    syncStateToActiveTab();
  }

  function onLogScroll() {
    const nearBottom = els.logViewer.scrollTop + els.logViewer.clientHeight >= els.logViewer.scrollHeight - 24;
    state.autoFollow = nearBottom;
    els.backToBottomBtn.hidden = nearBottom;
    syncStateToActiveTab();
    updateModeUI();
    if (els.logViewer.scrollTop < 24) {
      loadPrevChunk();
    }
  }

  function bindEvents() {
    els.serverSelect.addEventListener("change", async () => {
      const nextServerId = els.serverSelect.value;
      const hasOpenTabs = getVisibleTabs().length > 0;
      if (hasOpenTabs && nextServerId !== state.serverId) {
        const now = Date.now();
        const pending = state.pendingServerSwitchConfirm;
        const confirmed = pending
          && pending.serverId === nextServerId
          && now - pending.ts < 8000;
        if (!confirmed) {
          state.pendingServerSwitchConfirm = { serverId: nextServerId, ts: now };
          if (state.serverId) {
            els.serverSelect.value = state.serverId;
          }
          notify("切换服务器会关闭当前全部Tab，请再次选择目标服务器确认", "warn", 3600);
          return;
        }
      }
      state.pendingServerSwitchConfirm = null;
      state.tabs.forEach((t) => shutdownTabSocket(t, false));
      state.tabs = [];
      state.activeTabId = null;
      state.serverId = nextServerId;
      state.l1FilterInitialized = false;
      updateServerMeta();
      state.projectPath = null;
      ensureAtLeastOneTab();
      clearViewer();
      renderLogTabs();
      await loadProjects();
      await openDefaultStarredProjectIfNeeded();
    });
    els.refreshProjectsBtn.addEventListener("click", () => loadProjects().catch(showError));
    if (els.logTabs) {
      els.logTabs.addEventListener("click", (e) => {
        const target = e.target;
        const item = target && target.closest ? target.closest(".log-tab-item") : null;
        if (!item) return;
        const tabId = item.dataset.tabId;
        if (!tabId) return;
        const closeBtn = target && target.closest ? target.closest(".log-tab-close") : null;
        if (closeBtn) {
          e.preventDefault();
          e.stopPropagation();
          closeTab(tabId);
          return;
        }
        activateTab(tabId).catch(showError);
      });
    }
    els.testConnBtn.addEventListener("click", async () => {
      if (!state.serverId) return;
      setStatus("测试连接中...");
      await api("/api/servers/test-connection", {
        method: "POST",
        body: JSON.stringify({ serverId: state.serverId })
      });
      setStatus("连接成功");
    });
    els.configBtn.addEventListener("click", async () => {
      await reloadConfigEditor();
      els.configModal.classList.remove("hidden");
    });
    els.closeConfigBtn.addEventListener("click", () => els.configModal.classList.add("hidden"));
    if (els.addServerFormBtn) {
      els.addServerFormBtn.addEventListener("click", () => {
        if (!els.serverFormList) return;
        const idx = els.serverFormList.querySelectorAll(".server-form-row").length;
        els.serverFormList.appendChild(createServerFormRow({}, idx));
        refreshServerFormIndexes();
      });
    }
    els.reloadConfigBtn.addEventListener("click", () => reloadConfigEditor().catch(showError));
    els.saveConfigBtn.addEventListener("click", async () => {
      const payload = collectConfigFormPayload();
      const saved = await api("/api/bootstrap/servers", { method: "PUT", body: JSON.stringify(payload) });
      state.bootstrap = saved;
      state.l1FilterInitialized = false;
      renderServerSelect();
      renderConfigForm(saved);
      els.configModal.classList.add("hidden");
      await loadProjects();
      await openDefaultStarredProjectIfNeeded();
      notify("配置已保存", "info", 1800);
    });
    els.projectFilter.addEventListener("input", renderProjectTree);
    if (els.l1Filter) {
      els.l1Filter.addEventListener("change", renderProjectTree);
    }
    if (els.fileFilter) {
      els.fileFilter.addEventListener("input", () => {
        renderFiles();
      });
    }
    document.querySelectorAll(".file-type-tab[data-log-type]").forEach((btn) => {
      btn.addEventListener("click", () => switchProjectLogTypeTab(btn.dataset.logType).catch(showError));
    });
    if (els.fileSelect) {
      const tryRefresh = () => autoRefreshFilesFromSelect().catch(showError);
      els.fileSelect.addEventListener("pointerdown", tryRefresh);
      els.fileSelect.addEventListener("focus", tryRefresh);
      els.fileSelect.addEventListener("change", async () => {
        updateFileMeta();
        if (state.projectPath && els.fileSelect.value) {
          await openSelectedFile();
        }
      });
    }
    els.openLatestBtn.addEventListener("click", () => openLatestRealtime().catch(showError));
    if (els.realtimeSwitch) {
      els.realtimeSwitch.addEventListener("change", () => {
        setRealtimeFollowEnabled(!!els.realtimeSwitch.checked).catch(showError);
      });
    }
    if (els.drawerToggleBtn) {
      els.drawerToggleBtn.addEventListener("click", () => {
        applyFilterDrawerCollapsed(!state.drawerCollapsed);
      });
    }
    if (els.sidebarResizer && els.appBody) {
      let resizing = false;
      let startX = 0;
      let startWidth = 0;
      const onMove = (e) => {
        if (!resizing) return;
        const delta = e.clientX - startX;
        applySidebarWidth(startWidth + delta);
      };
      const onUp = () => {
        if (!resizing) return;
        resizing = false;
        document.body.classList.remove("resizing-sidebar");
        window.removeEventListener("pointermove", onMove);
        window.removeEventListener("pointerup", onUp);
      };
      els.sidebarResizer.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        resizing = true;
        startX = e.clientX;
        startWidth = state.sidebarWidthPx || (els.sidebar ? els.sidebar.getBoundingClientRect().width : 250);
        document.body.classList.add("resizing-sidebar");
        window.addEventListener("pointermove", onMove);
        window.addEventListener("pointerup", onUp);
      });
    }
    window.addEventListener("resize", () => {
      applySidebarWidth(state.sidebarWidthPx || 250);
    });
    if (els.closeOtherTabsBtn) {
      els.closeOtherTabsBtn.addEventListener("click", closeOtherTabs);
    }
    if (els.closeRightTabsBtn) {
      els.closeRightTabsBtn.addEventListener("click", closeRightTabs);
    }
    if (els.searchBtn) {
      els.searchBtn.addEventListener("click", () => searchLogs().catch(showError));
    }
    if (els.searchResetBtn) {
      els.searchResetBtn.addEventListener("click", resetSearchPanel);
    }
    els.searchPrevBtn.addEventListener("click", () => moveSearchCursor(-1).catch(showError));
    els.searchNextBtn.addEventListener("click", () => moveSearchCursor(1).catch(showError));
    els.searchScope.addEventListener("change", () => {
      resetSearchNavigation();
      state.pendingAllSearchConfirm = null;
      clearSearchConfirmHint();
    });
    if (els.searchExpandAllBtn) {
      els.searchExpandAllBtn.addEventListener("click", () => setAllSearchGroupsCollapsed(false));
    }
    if (els.searchCollapseAllBtn) {
      els.searchCollapseAllBtn.addEventListener("click", () => setAllSearchGroupsCollapsed(true));
    }
    els.searchKeyword.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        searchLogs().catch(showError);
      }
    });
    if (els.logFontDownBtn) {
      els.logFontDownBtn.addEventListener("click", () => changeLogFontSize(-LOG_FONT_SIZE_STEP));
    }
    if (els.logFontResetBtn) {
      els.logFontResetBtn.addEventListener("click", resetLogFontSize);
    }
    if (els.logFontUpBtn) {
      els.logFontUpBtn.addEventListener("click", () => changeLogFontSize(LOG_FONT_SIZE_STEP));
    }
    els.backToBottomBtn.addEventListener("click", scrollToBottom);
    els.logViewer.addEventListener("scroll", onLogScroll);
    window.addEventListener("beforeunload", () => {
      for (const tab of state.tabs) {
        if (tab.ws && tab.ws.readyState === WebSocket.OPEN) {
          try {
            tab.ws.send(JSON.stringify({ type: "close_stream" }));
          } catch (e) {
            console.warn(e);
          }
        }
      }
    });
  }

  async function reloadConfigEditor() {
    const config = await api("/api/bootstrap");
    state.bootstrap = config;
    renderConfigForm(config);
  }

  function showError(err) {
    console.error(err);
    setStatus("操作失败", "error");
    notify(err && err.message ? err.message : String(err), "error", 4200);
  }

  function escapeHtml(s) {
    return (s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    }[c]));
  }

  function highlightText(text, keyword, caseSensitive) {
    if (!keyword) return escapeHtml(text);
    const escaped = escapeHtml(text);
    const source = escaped;
    const kw = escapeRegExp(keyword);
    const flags = caseSensitive ? "g" : "gi";
    return source.replace(new RegExp(kw, flags), (m) => `<mark>${m}</mark>`);
  }

  function escapeRegExp(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function formatBytes(v) {
    const n = Number(v || 0);
    if (n < 1024) return `${n} B`;
    if (n < 1024 ** 2) return `${(n / 1024).toFixed(1)} KB`;
    if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`;
    return `${(n / 1024 ** 3).toFixed(1)} GB`;
  }

  async function init() {
    bindEvents();
    state.starredProjectOrder = loadStarredProjectPaths();
    syncStarredProjectPathsMap();
    applyFilterDrawerCollapsed(loadFilterDrawerCollapsedPreference());
    applySidebarWidth(loadSidebarWidthPreference());
    ensureAtLeastOneTab();
    startTabIdleMonitor();
    applyLogFontSize(loadLogFontSizePreference());
    updateSearchNavigationUI();
    updateSearchGroupToolButtons();
    updateActionAvailability();
    await loadBootstrap();
    syncStateToActiveTab();
    renderLogTabs();
    await loadProjects();
    await openDefaultStarredProjectIfNeeded();
  }

  init().catch(showError);
})();
