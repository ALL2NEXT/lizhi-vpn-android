const SETTINGS_SECTIONS = ['subscriptions', 'about-page', 'update-management', 'registration-control'];
const settingsDirtySections = new Set();
const settingsDraftVersions = new Map();
const settingsSaveMessages = new Map();
const settingsSaveTimers = new Map();
const SETTINGS_SAVE_DEBOUNCE_MS = 400;
const SETTINGS_AUTO_SAVE_STORAGE_KEY = 'chimera.admin.settingsAutoSave';
let settingsSaveInFlight = '';
let settingsAutoSaveEnabled = true;
let settingsManualSaveRun = false;
let settingsFilling = false;
let settingsSession = 0;

function updateSettingsSaveControls() {
  const status = document.getElementById('settingsSaveStatus');
  const save = document.getElementById('settingsManualSave');
  const autoSave = document.getElementById('settingsAutoSaveToggle');
  const dirtyCount = settingsDirtySections.size;
  const failedMessage = SETTINGS_SECTIONS
    .map(function(section) { return settingsSaveMessages.get(section); })
    .find(function(message) { return message && message.error; });
  const isSaving = Boolean(settingsSaveInFlight || settingsManualSaveRun);

  if (status) {
    status.textContent = isSaving
      ? '正在保存配置…'
      : failedMessage
        ? failedMessage.text
        : dirtyCount
          ? settingsAutoSaveEnabled ? '有未保存修改，即将自动保存…' : '有未保存修改，请点击“保存配置”'
          : '所有修改已保存';
    status.classList.toggle('error', Boolean(failedMessage));
  }
  if (autoSave && autoSave.checked !== settingsAutoSaveEnabled) autoSave.checked = settingsAutoSaveEnabled;
  if (save) {
    save.disabled = isSaving || dirtyCount === 0;
    save.textContent = isSaving ? '保存中…' : '保存配置';
  }

  SETTINGS_SECTIONS.forEach(function(section) {
    const panel = document.querySelector('[data-panel="' + section + '"]');
    if (!panel) return;
    const dirty = settingsDirtySections.has(section);
    const saving = settingsSaveInFlight === section;
    panel.dataset.settingsDirty = dirty ? 'true' : 'false';
    panel.setAttribute('aria-busy', saving ? 'true' : 'false');
  });
}

function readSettingsAutoSavePreference() {
  try {
    const stored = window.localStorage.getItem(SETTINGS_AUTO_SAVE_STORAGE_KEY);
    return stored == null ? true : stored === 'true';
  } catch (_) {
    return true;
  }
}

function setSettingsAutoSaveEnabled(enabled) {
  settingsAutoSaveEnabled = Boolean(enabled);
  try {
    window.localStorage.setItem(SETTINGS_AUTO_SAVE_STORAGE_KEY, String(settingsAutoSaveEnabled));
  } catch (_) {}

  if (settingsAutoSaveEnabled) {
    SETTINGS_SECTIONS.forEach(function(section) {
      if (settingsDirtySections.has(section)) scheduleSettingsSave(section, 0);
    });
  } else {
    settingsSaveTimers.forEach(function(timer) { clearTimeout(timer); });
    settingsSaveTimers.clear();
  }
  updateSettingsSaveControls();
}

function scheduleSettingsSave(section, delay) {
  if (!settingsAutoSaveEnabled || settingsManualSaveRun || !SETTINGS_SECTIONS.includes(section) || !settingsDirtySections.has(section)) return;
  // 输入暂停后再提交，合并连续键入产生的保存请求。
  const previousTimer = settingsSaveTimers.get(section);
  if (settingsSaveTimers.has(section)) clearTimeout(previousTimer);
  const timer = setTimeout(function() {
    settingsSaveTimers.delete(section);
    if (!settingsAutoSaveEnabled || settingsManualSaveRun || !settingsDirtySections.has(section) || settingsSaveInFlight) return;
    void pushSection(section);
  }, delay == null ? SETTINGS_SAVE_DEBOUNCE_MS : delay);
  settingsSaveTimers.set(section, timer);
}

function resumePendingSettingsSaves(completedSection, completedVersion, succeeded) {
  if (!settingsAutoSaveEnabled || settingsManualSaveRun || settingsSaveInFlight) return;
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
  settingsManualSaveRun = false;
  updateSettingsSaveControls();
}

async function saveAllSettings() {
  if (!currentState || settingsSaveInFlight || settingsManualSaveRun) return false;
  const session = settingsSession;
  const attemptedVersions = new Map();
  settingsManualSaveRun = true;
  settingsSaveTimers.forEach(function(timer) { clearTimeout(timer); });
  settingsSaveTimers.clear();
  updateSettingsSaveControls();

  try {
    // 按模块串行保存，失败的草稿保留，避免覆盖尚未提交的修改。
    while (session === settingsSession) {
      const section = SETTINGS_SECTIONS.find(function(name) {
        return settingsDirtySections.has(name) &&
          attemptedVersions.get(name) !== (settingsDraftVersions.get(name) || 0);
      });
      if (!section) break;
      attemptedVersions.set(section, settingsDraftVersions.get(section) || 0);
      await pushSection(section);
    }
    return SETTINGS_SECTIONS.every(function(section) { return !settingsDirtySections.has(section); });
  } finally {
    if (session === settingsSession) {
      settingsManualSaveRun = false;
      updateSettingsSaveControls();
      if (settingsAutoSaveEnabled) {
        SETTINGS_SECTIONS.forEach(function(section) {
          const version = settingsDraftVersions.get(section) || 0;
          if (settingsDirtySections.has(section) && attemptedVersions.get(section) !== version) {
            scheduleSettingsSave(section, 0);
          }
        });
      }
    }
  }
}

function hasUnsavedAdminChanges() {
  return settingsDirtySections.size > 0;
}

function initializeSettingsDrafts() {
  settingsAutoSaveEnabled = readSettingsAutoSavePreference();
  const autoSaveToggle = document.getElementById('settingsAutoSaveToggle');
  const saveButton = document.getElementById('settingsManualSave');
  if (autoSaveToggle) {
    autoSaveToggle.checked = settingsAutoSaveEnabled;
    autoSaveToggle.addEventListener('change', function() {
      setSettingsAutoSaveEnabled(autoSaveToggle.checked);
    });
  }
  if (saveButton) saveButton.addEventListener('click', function() { void saveAllSettings(); });

  ['input', 'change'].forEach(function(type) {
    document.addEventListener(type, function(event) {
      if (!event.target.closest || event.target.id === 'registrationCodeCount' || event.target.id === 'registrationCodeMaxUses') return;
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
