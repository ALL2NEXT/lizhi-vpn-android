function formatMegabytes(megabytes) {
  let value = Number(megabytes) || 0;
  const units = ['MB', 'GB', 'TB', 'PB'];
  let index = 0;
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
  if (value === 0) return '0 MB';
  if (index === 0 && value < 0.01) return '<0.01 MB';
  return value.toFixed(value >= 100 ? 0 : value >= 10 ? 1 : 2) + ' ' + units[index];
}
function csvMegabytes(megabytes) {
  return Math.round((Number(megabytes) || 0) * 10000) / 10000;
}
function usageTotal(usage) { return formatMegabytes(usage && usage.totalMb); }
function loadUserColumnWidths() {
  try {
    const stored = JSON.parse(
      localStorage.getItem(USER_COLUMN_WIDTHS_KEY) ||
      localStorage.getItem(LEGACY_USER_COLUMN_WIDTHS_KEY) ||
      '{}',
    );
    return stored && typeof stored === 'object' ? stored : {};
  } catch (_) {
    return {};
  }
}
function persistUserColumnWidths() {
  try { localStorage.setItem(USER_COLUMN_WIDTHS_KEY, JSON.stringify(userColumnWidths)); } catch (_) {}
}
function userColumnDefinition(columnKey) {
  return USER_SHEET_COLUMNS.find(function(column) { return column.key === columnKey; });
}
function resolvedUserColumnWidth(column) {
  const storedWidth = Number(userColumnWidths[column.key]);
  return Number.isFinite(storedWidth)
    ? Math.min(Math.max(Math.round(storedWidth), column.min), 500)
    : column.width;
}
function userSheetColgroup() {
  return '<colgroup>' + USER_SHEET_COLUMNS.map(function(column) {
    return '<col data-user-column="' + column.key + '" style="width:' + resolvedUserColumnWidth(column) + 'px">';
  }).join('') + '</colgroup>';
}
function userSheetHeader() {
  return USER_SHEET_COLUMNS.map(function(column) {
    const sortable = column.key !== 'select' && column.key !== 'actions';
    const sortAttributes = sortable
      ? ' data-sort-column="' + column.key + '" tabindex="0" aria-sort="none" title="Click to sort"'
      : '';
    const heading = column.key === 'select'
      ? '<input type="checkbox" data-select-all-users title="全选当前用户" aria-label="全选当前用户">'
      : esc(column.label);
    return '<th data-user-column-header="' + column.key + '"' + sortAttributes + '>' + heading +
      (sortable ? '<span class="sort-indicator" aria-hidden="true"></span>' : '') +
      '<span class="column-resizer" data-resize-column="' + column.key +
      '" role="separator" tabindex="0" title="拖动调整列宽，双击恢复默认宽度"></span></th>';
  }).join('');
}
function userSortValue(row, columnKey) {
  const accountId = row.dataset.userRow || '';
  const user = currentUsersData && Array.isArray(currentUsersData.users)
    ? currentUsersData.users.find(function(item) { return item.uid === accountId; })
    : null;
  if (!user) return accountId;
  const field = function(name, fallback) {
    const control = row.querySelector('[data-user-field="' + name + '"]');
    return control ? control.value.trim() : fallback;
  };
  switch (columnKey) {
    case 'accountId': return user.uid;
    case 'nickname': return field('nickname', user.nickname);
    case 'brand': return field('brand', user.brand);
    case 'model': return field('model', user.model);
    case 'androidVersion': return field('androidVersion', user.androidVersion);
    case 'appVersion': return field('appVersion', user.appVersion);
    case 'activeToday': return user.activeToday ? 1 : 0;
    case 'activeMonth': return user.activeThisMonth ? 1 : 0;
    case 'trafficTodayUpload': return Number(user.today && user.today.uploadMb) || 0;
    case 'trafficTodayDownload': return Number(user.today && user.today.downloadMb) || 0;
    case 'trafficToday': return Number(user.today && user.today.totalMb) || 0;
    case 'trafficMonthUpload': return Number(user.month && user.month.uploadMb) || 0;
    case 'trafficMonthDownload': return Number(user.month && user.month.downloadMb) || 0;
    case 'trafficMonth': return Number(user.month && user.month.totalMb) || 0;
    case 'trafficTotalUpload': return Number(user.totalUploadMb) || 0;
    case 'trafficTotalDownload': return Number(user.totalDownloadMb) || 0;
    case 'trafficTotal': return Number(user.totalMb) || 0;
    case 'createdAt': return user.createdAt || '';
    case 'lastReportAt': return user.lastReportAt || '';
    default: return '';
  }
}
function compareUserSortValues(left, right, columnKey) {
  const numericColumns = [
    'activeToday', 'activeMonth',
    'trafficTodayUpload', 'trafficTodayDownload', 'trafficToday',
    'trafficMonthUpload', 'trafficMonthDownload', 'trafficMonth',
    'trafficTotalUpload', 'trafficTotalDownload', 'trafficTotal',
  ];
  if (numericColumns.includes(columnKey)) return Number(left) - Number(right);
  const leftText = String(left == null ? '' : left);
  const rightText = String(right == null ? '' : right);
  if (!leftText && rightText) return 1;
  if (leftText && !rightText) return -1;
  if (!leftText && !rightText) return 0;

  const dateColumns = ['createdAt', 'lastReportAt'];
  if (dateColumns.includes(columnKey)) {
    const leftTime = Date.parse(leftText);
    const rightTime = Date.parse(rightText);

    if (Number.isFinite(leftTime) && Number.isFinite(rightTime)) return leftTime - rightTime;
  }
  return leftText.localeCompare(rightText, 'zh-CN', {numeric: true, sensitivity: 'base'});
}
function updateUserSortIndicators(table) {
  if (!table) return;
  table.querySelectorAll('[data-sort-column]').forEach(function(header) {
    const active = header.dataset.sortColumn === userSortKey;
    const indicator = header.querySelector('.sort-indicator');
    header.setAttribute('aria-sort', active
      ? (userSortDirection === 'desc' ? 'descending' : 'ascending')
      : 'none');
    header.title = active
      ? (userSortDirection === 'desc' ? 'Descending; click to switch to ascending' : 'Ascending; click to switch to descending')
      : 'Click to sort descending';
    if (indicator) indicator.textContent = active ? (userSortDirection === 'desc' ? '↓' : '↑') : '';
  });
}
function applyUserSort() {
  const table = el('usersTable').querySelector('table.user-sheet');
  if (!table || !userSortKey) return;
  const body = table.querySelector('tbody');
  if (!body) return;
  const rows = Array.from(body.querySelectorAll('[data-user-row]'));
  rows.map(function(row, index) {
    return {row: row, index: index};
  }).sort(function(left, right) {
    const result = compareUserSortValues(
      userSortValue(left.row, userSortKey),
      userSortValue(right.row, userSortKey),
      userSortKey,
    );
    return (userSortDirection === 'desc' ? -result : result) || left.index - right.index;
  }).forEach(function(item) {
    body.appendChild(item.row);
  });
  updateUserSortIndicators(table);
}
function toggleUserSort(columnKey) {
  if (userSortKey === columnKey) {
    userSortDirection = userSortDirection === 'desc' ? 'asc' : 'desc';
  } else {
    userSortKey = columnKey;
    userSortDirection = 'desc';
  }
  applyUserSort();
}
function updateUserSheetWidth(table) {
  if (!table) return;
  const totalWidth = USER_SHEET_COLUMNS.reduce(function(total, column) {
    const col = table.querySelector('col[data-user-column="' + column.key + '"]');
    return total + (col ? Number.parseFloat(col.style.width) || column.width : column.width);
  }, 0);
  table.style.width = Math.ceil(totalWidth) + 'px';
}
function setUserColumnWidth(table, columnKey, requestedWidth, persist) {
  const column = userColumnDefinition(columnKey);
  const col = table && table.querySelector('col[data-user-column="' + columnKey + '"]');
  if (!column || !col) return;
  const width = Math.min(Math.max(Math.round(requestedWidth), column.min), 500);
  col.style.width = width + 'px';
  userColumnWidths[columnKey] = width;
  updateUserSheetWidth(table);
  if (persist) persistUserColumnWidths();
}
function resetUserColumnWidths() {
  userColumnWidths = {};
  try { localStorage.removeItem(USER_COLUMN_WIDTHS_KEY); } catch (_) {}
  const table = el('usersTable').querySelector('table.user-sheet');
  if (table) {
    USER_SHEET_COLUMNS.forEach(function(column) {
      const col = table.querySelector('col[data-user-column="' + column.key + '"]');
      if (col) col.style.width = column.width + 'px';
    });
    updateUserSheetWidth(table);
  }
  show('列宽已恢复为紧凑默认值');
}
function updateUserSelectionUi() {
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'));
  const visibleRows = rows.filter(function(row) { return !row.hidden; });
  const selectedVisible = visibleRows.filter(function(row) {
    return selectedUserIds.has(row.dataset.userRow);
  });
  const selectAll = el('usersTable').querySelector('[data-select-all-users]');
  if (selectAll) {
    selectAll.checked = visibleRows.length > 0 && selectedVisible.length === visibleRows.length;
    selectAll.indeterminate = selectedVisible.length > 0 && selectedVisible.length < visibleRows.length;
  }
  el('usersTable').querySelectorAll('[data-user-select]').forEach(function(control) {
    control.checked = selectedUserIds.has(control.dataset.userSelect);
  });
  const status = el('userSelectionStatus');
  if (status) status.textContent = selectedUserIds.size ? '已选 ' + selectedUserIds.size + ' 个用户' : '';
  updateUserToolbar();
}
function toggleUserSelection(accountId, isSelected) {
  if (isSelected) selectedUserIds.add(accountId);
  else selectedUserIds.delete(accountId);
  updateUserSelectionUi();
}
function toggleSelectAllUsers() {
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'))
    .filter(function(row) { return !row.hidden; });
  const shouldSelect = rows.some(function(row) { return !selectedUserIds.has(row.dataset.userRow); });
  rows.forEach(function(row) {
    if (shouldSelect) selectedUserIds.add(row.dataset.userRow);
    else selectedUserIds.delete(row.dataset.userRow);
  });
  updateUserSelectionUi();
}
function attachUserColumnResizers() {
  const table = el('usersTable').querySelector('table.user-sheet');
  if (!table) return;
  updateUserSheetWidth(table);
  updateUserSortIndicators(table);
  table.querySelectorAll('[data-sort-column]').forEach(function(header) {
    header.onclick = function(event) {
      if (event.target.closest('[data-resize-column]')) return;
      toggleUserSort(header.dataset.sortColumn);
    };
    header.onkeydown = function(event) {
      if (event.target.closest('[data-resize-column]')) return;
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      toggleUserSort(header.dataset.sortColumn);
    };
  });
  const selectAll = table.querySelector('[data-select-all-users]');
  if (selectAll) selectAll.onchange = toggleSelectAllUsers;
  table.querySelectorAll('[data-resize-column]').forEach(function(handle) {
    const columnKey = handle.dataset.resizeColumn;
    handle.onpointerdown = function(event) {
      if (event.button !== 0) return;
      event.preventDefault();
      const col = table.querySelector('col[data-user-column="' + columnKey + '"]');
      const startWidth = col ? Number.parseFloat(col.style.width) : 0;
      const startX = event.clientX;
      document.body.classList.add('column-resizing');
      function move(moveEvent) {
        setUserColumnWidth(table, columnKey, startWidth + moveEvent.clientX - startX, false);
      }
      function stop() {
        document.removeEventListener('pointermove', move);
        document.removeEventListener('pointerup', stop);
        document.removeEventListener('pointercancel', stop);
        document.body.classList.remove('column-resizing');
        persistUserColumnWidths();
      }
      document.addEventListener('pointermove', move);
      document.addEventListener('pointerup', stop);
      document.addEventListener('pointercancel', stop);
    };
    handle.ondblclick = function(event) {
      event.preventDefault();
      const column = userColumnDefinition(columnKey);
      if (!column) return;
      delete userColumnWidths[columnKey];
      setUserColumnWidth(table, columnKey, column.width, false);
      persistUserColumnWidths();
    };
    handle.onkeydown = function(event) {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      const col = table.querySelector('col[data-user-column="' + columnKey + '"]');
      const currentWidth = col ? Number.parseFloat(col.style.width) : 0;
      setUserColumnWidth(table, columnKey, currentWidth + (event.key === 'ArrowRight' ? 10 : -10), true);
    };
  });
  applyUserSort();
}
function userRowSearchText(row) {
  const values = [row.dataset.userRow || ''];
  row.querySelectorAll('[data-user-field]').forEach(function(control) {
    values.push(control.value || '');
  });
  row.querySelectorAll('td.readonly').forEach(function(cell) {
    values.push(cell.textContent || '');
  });
  return values.join(' ').toLowerCase();
}
function applyUserSearch(resetPosition) {
  const input = el('userSearch');
  const status = el('userSearchStatus');
  const query = input ? input.value.trim().toLowerCase() : '';
  const rows = Array.from(el('usersTable').querySelectorAll('[data-user-row]'));
  userSearchMatches = [];
  rows.forEach(function(row) {
    const matched = !query || userRowSearchText(row).includes(query);
    row.hidden = !matched;
    row.classList.remove('search-current');
    if (matched) userSearchMatches.push(row);
  });
  if (resetPosition !== false || userSearchIndex >= userSearchMatches.length) {
    userSearchIndex = -1;
  }
  if (userSearchIndex >= 0 && userSearchMatches[userSearchIndex]) {
    userSearchMatches[userSearchIndex].classList.add('search-current');
  }
  if (status) {
    status.textContent = query
      ? '匹配 ' + userSearchMatches.length + ' / ' + rows.length
      : '';
  }
  updateUserSelectionUi();
}
function locateUserSearch(direction) {
  const input = el('userSearch');
  if (!input || !input.value.trim()) {
    if (input) input.focus();
    show('请先输入搜索内容', true);
    return;
  }
  applyUserSearch(false);
  if (userSearchMatches.length === 0) {
    show('没有找到匹配的用户', true);
    return;
  }
  userSearchMatches.forEach(function(row) { row.classList.remove('search-current'); });
  const step = direction < 0 ? -1 : 1;
  userSearchIndex = (userSearchIndex + step + userSearchMatches.length) % userSearchMatches.length;
  const row = userSearchMatches[userSearchIndex];
  row.classList.add('search-current');
  row.scrollIntoView({behavior: 'smooth', block: 'center', inline: 'nearest'});
  el('userSearchStatus').textContent =
    '第 ' + (userSearchIndex + 1) + ' / ' + userSearchMatches.length + ' 个匹配';
}
function handleUserSearchKey(event) {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  locateUserSearch(event.shiftKey ? -1 : 1);
}
function clearUserSearch() {
  const input = el('userSearch');
  if (!input) return;
  input.value = '';
  applyUserSearch();
  input.focus();
}
