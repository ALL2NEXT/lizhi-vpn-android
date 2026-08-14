let experienceAwardsData = null;
let experienceAwardsLoading = false;

function renderExperienceAwards() {
  const host = el('experienceAwardsTable');
  if (!host) return;
  const awards = experienceAwardsData && Array.isArray(experienceAwardsData.awards)
    ? experienceAwardsData.awards
    : [];
  if (awards.length === 0) {
    host.innerHTML = '<div class="user-table-empty">暂无经验发放记录</div>';
    return;
  }
  const rows = awards.map(function(award) {
    return '<tr>' +
      '<td>' + esc(award.createdAt || '暂无') + '</td>' +
      '<td><code>' + esc(award.uid || '已删除账户') + '</code></td>' +
      '<td>' + esc(award.nickname || '暂无') + '</td>' +
      '<td>' + esc(award.experienceAwarded || 0) + '</td>' +
      '<td>' + esc(award.reason || '暂无') + '</td>' +
      '<td>' + esc(award.accountStatus || '账户已删除') + '</td>' +
      '</tr>';
  }).join('');
  host.innerHTML = '<table class="user-data-table" aria-label="经验发放记录">' +
    '<thead><tr><th>发放时间</th><th>UID</th><th>昵称</th><th>经验</th><th>审核说明</th><th>账户状态</th></tr></thead>' +
    '<tbody>' + rows + '</tbody></table>';
}

async function loadExperienceAwards(force) {
  if (experienceAwardsLoading) return false;
  if (experienceAwardsData && !force) {
    renderExperienceAwards();
    return true;
  }
  const host = el('experienceAwardsTable');
  experienceAwardsLoading = true;
  if (host) host.innerHTML = '<div class="loading-state">正在读取经验发放记录</div>';
  try {
    const response = await fetch(ADMIN_API_BASE + '/experience-awards', {headers: headers()});
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
    experienceAwardsData = data;
    renderExperienceAwards();
    return true;
  } catch (error) {
    if (host) host.innerHTML = '<div class="error-state">经验发放记录读取失败</div>';
    show('经验发放记录读取失败：' + (error.message || error), true);
    return false;
  } finally {
    experienceAwardsLoading = false;
  }
}

async function grantUserExperience() {
  const uid = value('experienceRewardUid');
  const experience = Number(value('experienceRewardAmount'));
  const reason = value('experienceRewardReason');
  if (!uid || !Number.isSafeInteger(experience) || experience < 1 || !reason) {
    show('请填写有效的用户 UID、正整数经验值和审核说明', true);
    return;
  }
  if (!confirm('确认向用户 ' + uid + ' 发放 ' + experience + ' 点经验吗？')) return;
  try {
    const response = await fetch(ADMIN_API_BASE + '/experience-awards', {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({uid: uid, experience: experience, reason: reason})
    });
    const responseText = await response.text();
    const data = parseResponseJson(responseText);
    if (!response.ok) {
      show(adminErrorMessage(data.error, '经验发放失败（HTTP ' + response.status + '）'), true);
      return;
    }
    setValue('experienceRewardAmount', '');
    setValue('experienceRewardReason', '');
    await loadExperienceAwards(true);
    show('经验发放成功，实际增加 ' + (Number(data.experienceAwarded) || 0) + ' 点经验');
  } catch (error) {
    show('经验发放失败：' + (error.message || error), true);
  }
}
