// 统一常见厂商及其子品牌，避免同一厂商因大小写或品牌写法不同被拆分。
function normalizedBrand(value) {
  const raw = String(value == null ? '' : value).trim();
  if (!raw) return '未知厂商';
  const brand = raw.toLowerCase().replace(/[\s_-]+/g, '');
  if (/^(oppo|oneplus|一加|realme|真我)/.test(brand)) return 'OPPO';
  if (/^(vivo|iqoo)/.test(brand)) return 'vivo';
  if (/^(xiaomi|redmi|poco|mi|小米)/.test(brand)) return '小米';
  if (/^(huawei|华为)/.test(brand)) return '华为';
  if (/^(honor|荣耀)/.test(brand)) return '荣耀';

  if (/^(samsung|三星)/.test(brand)) return '三星';
  if (/^(google|pixel)/.test(brand)) return 'Google';
  if (/^(motorola|moto|lenovo|联想)/.test(brand)) return '联想 / Motorola';
  if (/^(zte|nubia|红魔|中兴)/.test(brand)) return '中兴 / nubia';
  if (/^(meizu|魅族)/.test(brand)) return '魅族';
  if (/^(sony|索尼)/.test(brand)) return 'Sony';
  if (/^(asus|rog|华硕)/.test(brand)) return '华硕';
  if (/^(nothing)/.test(brand)) return 'Nothing';
  return raw;
}
function normalizedAndroidVersion(value) {
  const version = String(value == null ? '' : value).trim().replace(/^android\s*/i, '');
  return version ? 'Android ' + version : '未知版本';
}
function normalizedAppVersion(value) {
  const version = String(value == null ? '' : value).trim().replace(/^v/i, '');
  return version ? 'v' + version : '未知版本';
}
function distributionForUsers(users, labelForUser) {
  const groups = new Map();
  users.forEach(function(user) {
    const label = labelForUser(user);
    const key = label.toLocaleLowerCase('zh-CN');
    const current = groups.get(key);
    if (current) current.count++;
    else groups.set(key, {label: label, count: 1});
  });
  return Array.from(groups.values()).sort(function(left, right) {
    return right.count - left.count || left.label.localeCompare(right.label, 'zh-CN', {
      numeric: true,
      sensitivity: 'base'
    });
  });
}
function growthPointLabel(key, period) {
  const parts = key.split('-').map(Number);
  if (period === 'month') return parts[0] + '年' + parts[1] + '月';
  return parts[1] + '月' + parts[2] + '日';
}
function renderGrowthChart(data) {
  const host = el('userGrowthChart');
  const summary = el('userGrowthSummary');
  if (!host) return;
  const period = data && (data.period === 'week' || data.period === 'month')
    ? data.period
    : 'day';
  const points = data && Array.isArray(data.points)
    ? data.points.map(function(point) {
      return {
        key: String(point.key || ''),
        newUsers: Math.max(0, Number(point.newUsers) || 0),
        totalUsers: Math.max(0, Number(point.totalUsers) || 0)
      };
    }).filter(function(point) { return /^\d{4}-\d{2}(-\d{2})?$/.test(point.key); })
    : [];
  if (points.length === 0) {
    host.innerHTML = '<div class="loading-state growth-empty">暂无可统计的注册数据</div>';
    if (summary) summary.textContent = '';
    return;
  }

  const width = 920;
  const height = 330;
  const padding = {top: 20, right: 24, bottom: 48, left: 54};
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const maxValue = Math.max(1, points.reduce(function(maximum, point) {
    return Math.max(maximum, point.totalUsers);
  }, 0));
  const tickStep = Math.max(1, Math.ceil(maxValue / 4));
  const chartMax = tickStep * 4;
  const xFor = function(index) {
    return points.length === 1
      ? padding.left + plotWidth / 2
      : padding.left + index / (points.length - 1) * plotWidth;
  };
  const yFor = function(value) {
    return padding.top + plotHeight - value / chartMax * plotHeight;
  };
  const linePath = points.map(function(point, index) {
    return (index === 0 ? 'M' : 'L') + ' ' + xFor(index).toFixed(2) + ' ' + yFor(point.totalUsers).toFixed(2);
  }).join(' ');
  const grid = Array.from({length: 5}, function(_, index) {
    const value = tickStep * index;
    const y = yFor(value).toFixed(2);
    return '<line x1="' + padding.left + '" y1="' + y + '" x2="' + (width - padding.right) +
      '" y2="' + y + '" stroke="#dadce0" stroke-width="1" />' +
      '<text x="' + (padding.left - 10) + '" y="' + (Number(y) + 4) +
      '" text-anchor="end" fill="#5f6368" font-size="12">' + value + '</text>';
  }).join('');
  const labelEvery = Math.max(1, Math.ceil(points.length / 8));
  const labels = points.map(function(point, index) {
    const isLast = index === points.length - 1;
    if (index !== 0 && !isLast && index % labelEvery !== 0) return '';
    return '<text x="' + xFor(index).toFixed(2) + '" y="' + (height - 16) +
      '" text-anchor="middle" fill="#5f6368" font-size="12">' +
      esc(growthPointLabel(point.key, period)) + '</text>';
  }).join('');
  const circles = points.map(function(point, index) {
    const label = growthPointLabel(point.key, period);
    return '<circle cx="' + xFor(index).toFixed(2) + '" cy="' + yFor(point.totalUsers).toFixed(2) +
      '" r="4" fill="#1a73e8" stroke="#ffffff" stroke-width="2"><title>' +
      esc(label + '：累计 ' + point.totalUsers + '，新增 ' + point.newUsers) + '</title></circle>';
  }).join('');
  host.innerHTML = '<svg class="growth-chart" viewBox="0 0 ' + width + ' ' + height +
    '" role="img" aria-label="' + esc('用户增长折线图，按' + (period === 'day' ? '日' : period === 'week' ? '周' : '月') + '统计') + '">' +
    grid +
    '<line x1="' + padding.left + '" y1="' + (padding.top + plotHeight) + '" x2="' +
    (width - padding.right) + '" y2="' + (padding.top + plotHeight) + '" stroke="#80868b" stroke-width="1" />' +
    '<path d="' + linePath + '" fill="none" stroke="#1a73e8" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />' +
    circles + labels +
    '</svg>';
  if (summary) {
    const latest = points[points.length - 1];
    summary.innerHTML = '<span>当前累计 <strong>' + latest.totalUsers + '</strong> 个用户</span>' +
      '<span>最近' + (period === 'day' ? '一天' : period === 'week' ? '一周' : '一月') +
      '新增 <strong>' + latest.newUsers + '</strong> 个</span>' +
      '<span class="growth-legend"><i></i>累计用户</span>';
  }
}
function renderActivityChart(data) {
  const host = el('userActivityChart');
  const summary = el('userActivitySummary');
  if (!host) return;
  const period = data && (data.period === 'week' || data.period === 'month')
    ? data.period
    : 'day';
  const points = data && Array.isArray(data.points)
    ? data.points.map(function(point) {
      return {
        key: String(point.key || ''),
        activeUsers: Math.max(0, Number(point.activeUsers) || 0)
      };
    }).filter(function(point) { return /^\d{4}-\d{2}(-\d{2})?$/.test(point.key); })
    : [];
  if (points.length === 0) {
    host.innerHTML = '<div class="loading-state growth-empty">当前范围暂无活跃用户数据</div>';
    if (summary) summary.textContent = '';
    return;
  }

  const width = 920;
  const height = 330;
  const padding = {top: 20, right: 24, bottom: 48, left: 54};
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const maxValue = Math.max(1, points.reduce(function(maximum, point) {
    return Math.max(maximum, point.activeUsers);
  }, 0));
  const tickStep = Math.max(1, Math.ceil(maxValue / 4));
  const chartMax = tickStep * 4;
  const xFor = function(index) {
    return points.length === 1
      ? padding.left + plotWidth / 2
      : padding.left + index / (points.length - 1) * plotWidth;
  };
  const yFor = function(value) {
    return padding.top + plotHeight - value / chartMax * plotHeight;
  };
  const linePath = points.map(function(point, index) {
    return (index === 0 ? 'M' : 'L') + ' ' + xFor(index).toFixed(2) + ' ' + yFor(point.activeUsers).toFixed(2);
  }).join(' ');
  const grid = Array.from({length: 5}, function(_, index) {
    const value = tickStep * index;
    const y = yFor(value).toFixed(2);
    return '<line x1="' + padding.left + '" y1="' + y + '" x2="' + (width - padding.right) +
      '" y2="' + y + '" stroke="#dadce0" stroke-width="1" />' +
      '<text x="' + (padding.left - 10) + '" y="' + (Number(y) + 4) +
      '" text-anchor="end" fill="#5f6368" font-size="12">' + value + '</text>';
  }).join('');
  const labelEvery = Math.max(1, Math.ceil(points.length / 8));
  const labels = points.map(function(point, index) {
    const isLast = index === points.length - 1;
    if (index !== 0 && !isLast && index % labelEvery !== 0) return '';
    return '<text x="' + xFor(index).toFixed(2) + '" y="' + (height - 16) +
      '" text-anchor="middle" fill="#5f6368" font-size="12">' +
      esc(growthPointLabel(point.key, period)) + '</text>';
  }).join('');
  const circles = points.map(function(point, index) {
    const label = growthPointLabel(point.key, period);
    return '<circle cx="' + xFor(index).toFixed(2) + '" cy="' + yFor(point.activeUsers).toFixed(2) +
      '" r="4" fill="#34a853" stroke="#ffffff" stroke-width="2"><title>' +
      esc(label + '：活跃用户 ' + point.activeUsers) + '</title></circle>';
  }).join('');
  host.innerHTML = '<svg class="growth-chart" viewBox="0 0 ' + width + ' ' + height +
    '" role="img" aria-label="' + esc('活跃用户趋势图，按' + (period === 'day' ? '日' : period === 'week' ? '周' : '月') + '统计') + '">' +
    grid +
    '<line x1="' + padding.left + '" y1="' + (padding.top + plotHeight) + '" x2="' +
    (width - padding.right) + '" y2="' + (padding.top + plotHeight) + '" stroke="#80868b" stroke-width="1" />' +
    '<path d="' + linePath + '" fill="none" stroke="#34a853" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />' +
    circles + labels +
    '</svg>';
  if (summary) {
    const latest = points[points.length - 1];
    const peak = points.reduce(function(maximum, point) {
      return Math.max(maximum, point.activeUsers);
    }, 0);
    summary.innerHTML = '<span>当前周期活跃 <strong>' + latest.activeUsers + '</strong> 个用户</span>' +
      '<span>范围峰值 <strong>' + peak + '</strong> 个用户</span>' +
      '<span class="growth-legend"><i style="background:#34a853"></i>活跃用户</span>';
  }
}
function renderTrafficChart(data) {
  const host = el('trafficChart');
  const summary = el('trafficSummary');
  if (!host) return;
  const period = data && (data.period === 'week' || data.period === 'month')
    ? data.period
    : 'day';
  const points = data && Array.isArray(data.points)
    ? data.points.map(function(point) {
      const uploadMb = Math.max(0, Number(point.uploadMb) || 0);
      const downloadMb = Math.max(0, Number(point.downloadMb) || 0);
      return {
        key: String(point.key || ''),
        uploadMb: uploadMb,
        downloadMb: downloadMb,
        totalMb: uploadMb + downloadMb
      };
    }).filter(function(point) { return /^\d{4}-\d{2}(-\d{2})?$/.test(point.key); })
    : [];
  if (points.length === 0) {
    host.innerHTML = '<div class="loading-state growth-empty">当前范围暂无流量数据</div>';
    if (summary) summary.textContent = '';
    return;
  }

  const width = 920;
  const height = 330;
  const padding = {top: 20, right: 24, bottom: 48, left: 68};
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const maxValue = Math.max(1, points.reduce(function(maximum, point) {
    return Math.max(maximum, point.totalMb);
  }, 0));
  const tickStep = Math.max(1, Math.ceil(maxValue / 4));
  const chartMax = tickStep * 4;
  const xFor = function(index) {
    return points.length === 1
      ? padding.left + plotWidth / 2
      : padding.left + index / (points.length - 1) * plotWidth;
  };
  const yFor = function(value) {
    return padding.top + plotHeight - value / chartMax * plotHeight;
  };
  const grid = Array.from({length: 5}, function(_, index) {
    const value = tickStep * index;
    const y = yFor(value).toFixed(2);
    return '<line x1="' + padding.left + '" y1="' + y + '" x2="' + (width - padding.right) +
      '" y2="' + y + '" stroke="#dadce0" stroke-width="1" />' +
      '<text x="' + (padding.left - 10) + '" y="' + (Number(y) + 4) +
      '" text-anchor="end" fill="#5f6368" font-size="12">' + esc(formatMegabytes(value)) + '</text>';
  }).join('');
  const groupWidth = plotWidth / Math.max(points.length, 1);
  const barWidth = Math.max(8, Math.min(36, groupWidth * 0.58));
  const baseline = yFor(0);
  const bars = points.map(function(point, index) {
    const x = xFor(index) - barWidth / 2;
    const downloadY = yFor(point.downloadMb);
    const totalY = yFor(point.totalMb);
    const downloadHeight = Math.max(0, baseline - downloadY);
    const uploadHeight = Math.max(0, downloadY - totalY);
    const label = growthPointLabel(point.key, period);
    return '<rect x="' + x.toFixed(2) + '" y="' + downloadY.toFixed(2) +
      '" width="' + barWidth.toFixed(2) + '" height="' + downloadHeight.toFixed(2) +
      '" rx="2" fill="#a142f4"><title>' + esc(label + '：下载 ' + formatMegabytes(point.downloadMb)) +
      '</title></rect>' +
      '<rect x="' + x.toFixed(2) + '" y="' + totalY.toFixed(2) +
      '" width="' + barWidth.toFixed(2) + '" height="' + uploadHeight.toFixed(2) +
      '" rx="2" fill="#34a853"><title>' + esc(label + '：上传 ' + formatMegabytes(point.uploadMb)) +
      '</title></rect>';
  }).join('');
  const linePath = points.map(function(point, index) {
    return (index === 0 ? 'M' : 'L') + ' ' + xFor(index).toFixed(2) + ' ' + yFor(point.totalMb).toFixed(2);
  }).join(' ');
  const labelEvery = Math.max(1, Math.ceil(points.length / 8));
  const labels = points.map(function(point, index) {
    const isLast = index === points.length - 1;
    if (index !== 0 && !isLast && index % labelEvery !== 0) return '';
    return '<text x="' + xFor(index).toFixed(2) + '" y="' + (height - 16) +
      '" text-anchor="middle" fill="#5f6368" font-size="12">' +
      esc(growthPointLabel(point.key, period)) + '</text>';
  }).join('');
  const circles = points.map(function(point, index) {
    const label = growthPointLabel(point.key, period);
    return '<circle cx="' + xFor(index).toFixed(2) + '" cy="' + yFor(point.totalMb).toFixed(2) +
      '" r="4" fill="#f9ab00" stroke="#ffffff" stroke-width="2"><title>' +
      esc(label + '：总流量 ' + formatMegabytes(point.totalMb)) + '</title></circle>';
  }).join('');
  host.innerHTML = '<svg class="traffic-chart" viewBox="0 0 ' + width + ' ' + height +
    '" role="img" aria-label="' + esc('流量消耗图，按' + (period === 'day' ? '日' : period === 'week' ? '周' : '月') + '统计') + '">' +
    grid +
    '<line x1="' + padding.left + '" y1="' + baseline + '" x2="' + (width - padding.right) +
    '" y2="' + baseline + '" stroke="#80868b" stroke-width="1" />' +
    bars +
    '<path d="' + linePath + '" fill="none" stroke="#f9ab00" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />' +
    circles + labels +
    '</svg>';
  if (summary) {
    const totalUpload = points.reduce(function(total, point) { return total + point.uploadMb; }, 0);
    const totalDownload = points.reduce(function(total, point) { return total + point.downloadMb; }, 0);
    summary.innerHTML = '<span>范围上传 <strong>' + formatMegabytes(totalUpload) + '</strong></span>' +
      '<span>范围下载 <strong>' + formatMegabytes(totalDownload) + '</strong></span>' +
      '<span>范围总流量 <strong>' + formatMegabytes(totalUpload + totalDownload) + '</strong></span>' +
      '<span class="traffic-legend traffic-upload"><i></i>上传</span>' +
      '<span class="traffic-legend traffic-download"><i></i>下载</span>' +
      '<span class="traffic-legend traffic-total"><i></i>总流量</span>';
  }
}
function growthTodayDateKey() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(new Date()).reduce(function(result, part) {
    if (part.type !== 'literal') result[part.type] = part.value;
    return result;
  }, {});
  return parts.year + '-' + parts.month + '-' + parts.day;
}
function initializeGrowthRange() {
  const today = growthTodayDateKey();
  ['growthStartDate', 'growthEndDate'].forEach(function(id) {
    const input = el(id);
    if (input) input.max = today;
  });
}
function applyGrowthRange() {
  const start = value('growthStartDate');
  const end = value('growthEndDate');
  if (start && end && start > end) {

    show('开始日期不能晚于结束日期', true);
    return;
  }
  loadGrowthChart(growthPeriod);
}
function clearGrowthRange() {
  setValue('growthStartDate', '');
  setValue('growthEndDate', '');
  loadGrowthChart(growthPeriod);
}
function growthRequestQuery(period) {
  const params = new URLSearchParams();
  params.set('period', period);
  const start = value('growthStartDate');
  const end = value('growthEndDate');
  if (start) params.set('start', start);
  if (end) params.set('end', end);
  return params.toString();
}
function initializeActivityRange() {
  const today = growthTodayDateKey();
  ['activityStartDate', 'activityEndDate'].forEach(function(id) {
    const input = el(id);
    if (input) input.max = today;
  });
}
function applyActivityRange() {
  const start = value('activityStartDate');
  const end = value('activityEndDate');
  if (start && end && start > end) {
    show('开始日期不能晚于结束日期', true);
    return;
  }
  loadActivityChart(activityPeriod);
}
function clearActivityRange() {
  setValue('activityStartDate', '');
  setValue('activityEndDate', '');
  loadActivityChart(activityPeriod);
}
function activityRequestQuery(period) {
  const params = new URLSearchParams();
  params.set('period', period);
  const start = value('activityStartDate');
  const end = value('activityEndDate');
  if (start) params.set('start', start);
  if (end) params.set('end', end);
  return params.toString();
}
function initializeTrafficRange() {
  const today = growthTodayDateKey();
  ['trafficStartDate', 'trafficEndDate'].forEach(function(id) {
    const input = el(id);
    if (input) input.max = today;
  });
}
function applyTrafficRange() {
  const start = value('trafficStartDate');
  const end = value('trafficEndDate');
  if (start && end && start > end) {
    show('开始日期不能晚于结束日期', true);
    return;
  }
  loadTrafficChart(trafficPeriod);
}
function clearTrafficRange() {
  setValue('trafficStartDate', '');
  setValue('trafficEndDate', '');
  loadTrafficChart(trafficPeriod);
}
function trafficRequestQuery(period) {
  const params = new URLSearchParams();
  params.set('period', period);
  const start = value('trafficStartDate');
  const end = value('trafficEndDate');
  if (start) params.set('start', start);
  if (end) params.set('end', end);
  return params.toString();
}
function setTrafficPeriod(period) {
  if (period !== 'day' && period !== 'week' && period !== 'month') return;
  trafficPeriod = period;
  document.querySelectorAll('[data-traffic-period]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.trafficPeriod === period);
  });
  loadTrafficChart(period);
}
function setActivityPeriod(period) {
  if (period !== 'day' && period !== 'week' && period !== 'month') return;
  activityPeriod = period;
  document.querySelectorAll('[data-activity-period]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.activityPeriod === period);
  });
  loadActivityChart(period);
}
async function loadActivityChart(period) {
  const host = el('userActivityChart');
  const summary = el('userActivitySummary');
  const requestId = ++activityRequestId;
  if (host) host.innerHTML = '<div class="loading-state">正在读取用户活跃数据…</div>';
  if (summary) summary.textContent = '';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/activity?' + activityRequestQuery(period), {
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) throw new Error(data.error || ('用户活跃数据读取失败（HTTP ' + response.status + '）'));
    if (requestId === activityRequestId) renderActivityChart(data);
  } catch (error) {
    if (requestId !== activityRequestId || !host) return;
    host.innerHTML = '<div class="error-state growth-empty">用户活跃数据读取失败</div>';
    if (summary) summary.textContent = '';
  }
}
async function loadTrafficChart(period) {
  const host = el('trafficChart');
  const summary = el('trafficSummary');
  const requestId = ++trafficRequestId;
  if (host) host.innerHTML = '<div class="loading-state">正在读取流量数据…</div>';
  if (summary) summary.textContent = '';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/traffic?' + trafficRequestQuery(period), {
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) throw new Error(data.error || ('流量数据读取失败（HTTP ' + response.status + '）'));
    if (requestId === trafficRequestId) renderTrafficChart(data);
  } catch (error) {
    if (requestId !== trafficRequestId || !host) return;
    host.innerHTML = '<div class="error-state growth-empty">流量数据读取失败</div>';
    if (summary) summary.textContent = '';
  }
}
const TRAFFIC_DETAIL_SORT_FIELDS = {
  todayUploadMb: '今日上传',
  todayDownloadMb: '今日下载',
  todayTotalMb: '今日总流量',
  monthUploadMb: '本月上传',
  monthDownloadMb: '本月下载',
  monthTotalMb: '本月总流量',
  totalUploadMb: '累计上传',
  totalDownloadMb: '累计下载',
  totalMb: '累计总流量'
};

function setGrowthPeriod(period) {
  if (period !== 'day' && period !== 'week' && period !== 'month') return;
  growthPeriod = period;
  document.querySelectorAll('[data-growth-period]').forEach(function(button) {
    button.classList.toggle('active', button.dataset.growthPeriod === period);
  });
  loadGrowthChart(period);
}
async function loadGrowthChart(period) {
  const host = el('userGrowthChart');
  const summary = el('userGrowthSummary');
  const requestId = ++growthRequestId;
  if (host) host.innerHTML = '<div class="loading-state">正在读取用户增长数据…</div>';
  if (summary) summary.textContent = '';
  try {
    const response = await fetch(ADMIN_API_BASE + '/users/growth?' + growthRequestQuery(period), {
      headers: headers()
    });
    const responseText = await response.text();
    let data = {};
    try { data = JSON.parse(responseText); } catch (_) {}
    if (!response.ok) throw new Error(data.error || ('用户增长数据读取失败（HTTP ' + response.status + '）'));
    if (requestId === growthRequestId) renderGrowthChart(data);
  } catch (error) {
    if (requestId !== growthRequestId || !host) return;
    host.innerHTML = '<div class="error-state growth-empty">用户增长数据读取失败</div>';
    if (summary) summary.textContent = '';
  }
}
 function setDashboardState(className, message) {
   ['brandDistribution', 'androidDistribution', 'appVersionDistribution', 'userGrowthChart', 'userActivityChart', 'trafficChart'].forEach(function(id) {
    const host = el(id);
    if (host) host.innerHTML = '<div class="' + className + '">' + esc(message) + '</div>';
  });
  const summary = el('userGrowthSummary');
  if (summary) summary.textContent = '';
  const activitySummary = el('userActivitySummary');
  if (activitySummary) activitySummary.textContent = '';
  const trafficSummary = el('trafficSummary');
  if (trafficSummary) trafficSummary.textContent = '';
}
const DISTRIBUTION_PALETTE = [
  '#1a73e8', '#ea4335', '#fbbc04', '#34a853', '#a142f4', '#ff6d01',
  '#00acc1', '#c5221f', '#5f6368', '#9334e6', '#1e8e3e', '#e37400',
  '#12b5cb', '#b80672', '#7cb342', '#5e35b1'
];
function distributionColor(index) {
  if (index < DISTRIBUTION_PALETTE.length) return DISTRIBUTION_PALETTE[index];
  return 'hsl(' + Math.round(index * 137.508 % 360) + ', 62%, 47%)';
}
function distributionSlicePath(startAngle, endAngle, center, radius) {
  const startX = center + radius * Math.cos(startAngle);
  const startY = center + radius * Math.sin(startAngle);
  const endX = center + radius * Math.cos(endAngle);
  const endY = center + radius * Math.sin(endAngle);
  const largeArc = endAngle - startAngle > Math.PI ? 1 : 0;
  return 'M ' + center + ' ' + center + ' L ' + startX.toFixed(3) + ' ' + startY.toFixed(3) +
    ' A ' + radius + ' ' + radius + ' 0 ' + largeArc + ' 1 ' + endX.toFixed(3) + ' ' + endY.toFixed(3) + ' Z';
}
function renderDistribution(hostId, groups, total) {
  const host = el(hostId);
  if (!host) return;
  if (!total || groups.length === 0) {
    host.innerHTML = '<div class="loading-state distribution-empty">暂无可统计的账户数据</div>';
    return;
  }
  let angle = -Math.PI / 2;
  const slices = groups.map(function(group, index) {
    const percent = Math.round(group.count / total * 1000) / 10;
    const color = distributionColor(index);
    const sweep = group.count / total * Math.PI * 2;
    const startAngle = angle;
    angle += sweep;
    const description = group.label + ' (' + group.count + ', ' + percent + '%)';
    const title = '<title>' + esc(description) + '</title>';
    if (groups.length === 1 || sweep >= Math.PI * 2 - 0.000001) {
      return '<circle cx="90" cy="90" r="88" fill="' + color + '">' + title + '</circle>';
    }
    return '<path d="' + distributionSlicePath(startAngle, angle, 90, 88) +
      '" fill="' + color + '" stroke="#ffffff" stroke-width="1">' + title + '</path>';
  }).join('');
  const rows = groups.map(function(group, index) {
    const percent = Math.round(group.count / total * 1000) / 10;
    const color = distributionColor(index);
    const description = group.label + ' (' + group.count + ', ' + percent + '%)';
    return '<div class="distribution-row" title="' + esc(description) + '">' +
      '<span class="distribution-swatch" style="--distribution-color:' + color + '" aria-hidden="true"></span>' +
      '<span class="distribution-label">' + esc(group.label) + '</span>' +
      '<span class="distribution-value">' + group.count + ' 个 · ' + percent + '%</span></div>';
  }).join('');
  const card = host.closest('.distribution-card');
  const heading = card && card.querySelector('h3');
  const chartLabel = heading ? heading.textContent.trim() + '占比饼图' : '占比饼图';
  host.innerHTML = '<div class="distribution-total">共 ' + total + ' 个账户</div>' +
    '<div class="distribution-content">' +
    '<svg class="distribution-pie" viewBox="0 0 180 180" role="img" aria-label="' + esc(chartLabel) + '">' +
    slices + '</svg><div class="distribution-legend">' + rows + '</div></div>';
}
function renderDashboard(data) {
   const users = data && Array.isArray(data.users) ? data.users : [];
   renderDistribution('brandDistribution', distributionForUsers(users, function(user) {
     return normalizedBrand(user.brand);
   }), users.length);
   renderDistribution('androidDistribution', distributionForUsers(users, function(user) {
     return normalizedAndroidVersion(user.androidVersion);
   }), users.length);
   renderDistribution('appVersionDistribution', distributionForUsers(users, function(user) {
     return normalizedAppVersion(user.appVersion);
   }), users.length);
 }
const USER_TABLE_DEFINITIONS = [
  {
    key: 'users_mb',
    title: 'users_mb（用户表）',
    columns: [
      'id', 'uid', 'nickname', 'accountType', 'email', 'created_at',
    ],
  },
  {
    key: 'user_devices_mb',
    title: 'user_devices_mb（设备表）',
    columns: [
      'id', 'user_id', 'device_id', 'brand', 'model', 'android_version',
      'android_sdk', 'app_version', 'created_at', 'last_seen_at',
    ],
  },
  {
    key: 'user_usage_mb',
    title: 'user_usage_mb（流量统计表，Bytes）',
    columns: [
      'user_id',
      'session_id',
      'subscription_group_name',
      'started_at',
      'ended_at',
      'reported_at',
      'usage_day',
      'upload_bytes',
      'download_bytes',
    ],
  },
];
