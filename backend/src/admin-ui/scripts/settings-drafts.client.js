const SETTINGS_SECTIONS = ['subscriptions', 'navigation-page', 'about-page', 'app-update', 'registration-control', 'notice'];
const settingsDirtySections = new Set();
const settingsDraftVersions = new Map();
const settingsSaveMessages = new Map();
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
    const save = panel.querySelector('[data-settings-save]');
    const discard = panel.querySelector('[data-settings-discard]');
    const message = settingsSaveMessages.get(section);
    if (status) {
      status.textContent = saving ? '正在保存…' : message ? message.text : dirty ? '有未保存修改' : '所有修改已保存';
      status.classList.toggle('error', Boolean(message && message.error));
    }
    if (save) {
      save.disabled = Boolean(settingsSaveInFlight) || !dirty;
      const noticeEnabled = section === 'notice' && el('noticeEnabled') && el('noticeEnabled').checked;
      const noticeScheduled = section === 'notice' && el('noticeStartScheduled') && el('noticeStartScheduled').checked;
      const saveLabel = section !== 'notice'
        ? '保存配置'
        : noticeEnabled
          ? noticeScheduled ? '保存定时公告' : '保存并发布'
          : '保存草稿';
      save.textContent = saving ? '保存中…' : saveLabel;
    }
    if (discard) discard.disabled = saving || !dirty;
    panel.setAttribute('aria-busy', saving ? 'true' : 'false');
  });
}

function markSettingsDirty(section) {
  if (settingsFilling || !SETTINGS_SECTIONS.includes(section)) return;
  settingsDirtySections.add(section);
  settingsDraftVersions.set(section, (settingsDraftVersions.get(section) || 0) + 1);
  settingsSaveMessages.delete(section);
  updateSettingsSaveControls();
}

function resetSettingsDrafts() {
  settingsSession += 1;
  settingsDirtySections.clear();
  settingsDraftVersions.clear();
  settingsSaveMessages.clear();
  settingsSaveInFlight = '';
  updateSettingsSaveControls();
}

function hasUnsavedAdminChanges() {
  return settingsDirtySections.size > 0 || (typeof adminUserDirty !== 'undefined' && adminUserDirty.size > 0);
}

function discardSettingsSection(section) {
  if (settingsSaveInFlight === section || !settingsDirtySections.has(section)) return;
  if (!confirm('放弃当前模块的未保存修改？其他模块的草稿会保留。')) return;
  fillState(currentState, section);
  settingsDirtySections.delete(section);
  settingsSaveMessages.delete(section);
  updateSettingsSaveControls();
}

function initializeSettingsDrafts() {
  ['input', 'change'].forEach(function(type) {
    document.addEventListener(type, function(event) {
      if (!event.target.closest || event.target.id === 'registrationCodeCount' || event.target.id === 'registrationCodeMaxUses' || event.target.closest('#subscriptionImportDialog')) return;
      const panel = event.target.closest('.tab-panel[data-panel]');
      if (panel) {
        markSettingsDirty(panel.dataset.panel);
        if (panel.dataset.panel === 'subscriptions') updateSourceRowsMeta();
      }
    });
  });
  document.querySelectorAll('[data-settings-discard]').forEach(function(button) {
    button.addEventListener('click', function() { discardSettingsSection(button.dataset.settingsDiscard); });
  });
  window.addEventListener('beforeunload', function(event) {
    if (!hasUnsavedAdminChanges()) return;
    event.preventDefault();
    event.returnValue = '';
  });
  updateSettingsSaveControls();
}
