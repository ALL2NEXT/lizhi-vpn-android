const SETTINGS_SECTIONS = ['subscriptions', 'about-page', 'registration-control'];
const settingsDirtySections = new Set();
const settingsDraftVersions = new Map();
const settingsSaveMessages = new Map();
const settingsSaveTimers = new Map();
const SETTINGS_SAVE_DEBOUNCE_MS = 400;
let settingsSaveInFlight = '';
let settingsFilling = false;
let settingsSession = 0;

function updateSettingsSaveControls() {
  SETTINGS_SECTIONS.forEach(function(section) {
    const panel = document.querySelector('[data-panel="' + section + '"]');
    if (!panel) return;
    const dirty = settingsDirtySections.has(section);
    const saving = settingsSaveInFlight === section;
    panel.dataset.settingsDirty = dirty ? 'true' : 'false';
    const status = panel.querySelector('[data-settings-save-status]');
    const message = settingsSaveMessages.get(section);
    if (status) {
      status.textContent = saving ? '正在保存…' : message ? message.text : dirty ? '即将自动保存…' : '所有修改已保存';
      status.classList.toggle('error', Boolean(message && message.error));
    }
    panel.setAttribute('aria-busy', saving ? 'true' : 'false');
  });
}

function scheduleSettingsSave(section, delay) {
  if (!SETTINGS_SECTIONS.includes(section) || !settingsDirtySections.has(section)) return;
  // 输入暂停后再提交，合并连续键入产生的保存请求。
  const previousTimer = settingsSaveTimers.get(section);
  if (settingsSaveTimers.has(section)) clearTimeout(previousTimer);
  const timer = setTimeout(function() {
    settingsSaveTimers.delete(section);
    if (!settingsDirtySections.has(section) || settingsSaveInFlight) return;
    void pushSection(section);
  }, delay == null ? SETTINGS_SAVE_DEBOUNCE_MS : delay);
  settingsSaveTimers.set(section, timer);
}

function resumePendingSettingsSaves(completedSection, completedVersion, succeeded) {
  if (settingsSaveInFlight) return;
  // 串行保存期间变更的模块在当前请求完成后继续自动保存。
  SETTINGS_SECTIONS.forEach(function(section) {
    if (!settingsDirtySections.has(section) || settingsSaveTimers.has(section)) return;
    const version = settingsDraftVersions.get(section) || 0;
    if (section === completedSection && !succeeded && version === completedVersion) return;
    scheduleSettingsSave(section, 0);
  });
}

function markSettingsDirty(section) {
  if (settingsFilling || !SETTINGS_SECTIONS.includes(section)) return;
  settingsDirtySections.add(section);
  settingsDraftVersions.set(section, (settingsDraftVersions.get(section) || 0) + 1);
  settingsSaveMessages.delete(section);
  updateSettingsSaveControls();
  scheduleSettingsSave(section);
}

function resetSettingsDrafts() {
  settingsSession += 1;
  settingsSaveTimers.forEach(function(timer) { clearTimeout(timer); });
  settingsSaveTimers.clear();
  settingsDirtySections.clear();
  settingsDraftVersions.clear();
  settingsSaveMessages.clear();
  settingsSaveInFlight = '';
  updateSettingsSaveControls();
}

function hasUnsavedAdminChanges() {
  return settingsDirtySections.size > 0 || (typeof adminUserDirty !== 'undefined' && adminUserDirty.size > 0);
}

function initializeSettingsDrafts() {
  ['input', 'change'].forEach(function(type) {
    document.addEventListener(type, function(event) {
      if (!event.target.closest || event.target.id === 'registrationCodeCount' || event.target.id === 'registrationCodeMaxUses' || event.target.closest('#subscriptionImportDialog')) return;
      const panel = event.target.closest('.tab-panel[data-panel]');
      if (panel) {
        if (panel.dataset.panel === 'subscriptions' && !event.target.matches('[data-field], [data-editor-field]')) return;
        if (panel.dataset.panel === 'registration-control' && !event.target.matches('input[name="registrationMode"]')) return;
        markSettingsDirty(panel.dataset.panel);
        if (panel.dataset.panel === 'subscriptions') updateSourceRowsMeta();
      }
    });
  });
  window.addEventListener('beforeunload', function(event) {
    if (!hasUnsavedAdminChanges()) return;
    event.preventDefault();
    event.returnValue = '';
  });
  updateSettingsSaveControls();
}
