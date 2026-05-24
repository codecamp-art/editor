(function () {
  const searchForm = document.getElementById('searchForm');
  const clientQuery = document.getElementById('clientQuery');
  const queryButton = document.getElementById('queryButton');
  const resetButton = document.getElementById('resetButton');
  const status = document.getElementById('status');
  const candidateSection = document.getElementById('candidateSection');
  const candidateList = document.getElementById('candidateList');
  const summaryCard = document.getElementById('summaryCard');
  const positionsCard = document.getElementById('positionsCard');
  const summaryBody = document.getElementById('summaryBody');
  const positionsBody = document.getElementById('positionsBody');
  const positionCount = document.getElementById('positionCount');
  const copyButton = document.getElementById('copyButton');

  let currentResult = null;
  let selectedClient = null;
  let searchTimer = null;

  function setStatus(message) {
    status.textContent = message || '';
  }

  function formatAmount(value) {
    const number = Number(value || 0);
    return number.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  function formatRatio(value) {
    const number = Number(value || 0) * 100;
    return number.toLocaleString('en-US', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    });
  }

  function buildClipboardRows(result) {
    if (!result || !result.summary) {
      return [];
    }
    const summary = result.summary;
    const rows = [
      ['Client ID', 'Currency', 'Margin Available', 'Total Equity', 'Risk Ratio (%)'],
      [
        summary.clientId || result.clientId || '',
        summary.currency || '',
        formatAmount(summary.marginAvailable),
        formatAmount(summary.totalEquity),
        formatRatio(summary.riskRatio)
      ],
      ['', '', '', '', ''],
      ['Positions', 'Total Long', 'Total Short', 'Intraday Long', 'Intraday Short']
    ];

    (result.positions || []).forEach((position) => {
      rows.push([
        position.position || '',
        String(position.totalLong || 0),
        String(position.totalShort || 0),
        String(position.intradayLong || 0),
        String(position.intradayShort || 0)
      ]);
    });
    return rows;
  }

  function rowsToTsv(rows) {
    return rows.map((row) => row.join('\t')).join('\n');
  }

  function rowsToHtml(rows) {
    const escapeHtml = (value) => String(value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
    const body = rows.map((row, rowIndex) => {
      const cellTag = rowIndex === 0 || rowIndex === 3 ? 'th' : 'td';
      return `<tr>${row.map((cell) => `<${cellTag}>${escapeHtml(cell)}</${cellTag}>`).join('')}</tr>`;
    }).join('');
    return `<table>${body}</table>`;
  }

  function hideCandidates() {
    candidateSection.hidden = true;
    clientQuery.setAttribute('aria-expanded', 'false');
  }

  function clearResult() {
    currentResult = null;
    summaryBody.replaceChildren();
    positionsBody.replaceChildren();
    summaryCard.hidden = true;
    positionsCard.hidden = true;
    copyButton.disabled = true;
    positionCount.textContent = 'Showing 0 to 0 of 0 entries';
  }

  function resetPage() {
    clientQuery.value = '';
    selectedClient = null;
    hideCandidates();
    candidateList.replaceChildren();
    clearResult();
    setStatus('');
    clientQuery.focus();
  }

  function setSelectedClient(candidate) {
    selectedClient = candidate;
    clientQuery.value = `${candidate.clientId} ${candidate.clientName || ''}`.trim();
    hideCandidates();
    setStatus('');
  }

  function renderCandidates(candidates) {
    candidateList.replaceChildren();
    if (!candidates.length) {
      hideCandidates();
      setStatus('No matching clients');
      return;
    }

    candidates.forEach((candidate, index) => {
      const option = document.createElement('button');
      option.type = 'button';
      option.className = `candidate-option${index === 0 ? ' selected' : ''}`;
      option.setAttribute('role', 'option');
      option.dataset.clientId = candidate.clientId;

      const id = document.createElement('span');
      id.className = 'candidate-id';
      id.textContent = candidate.clientId;
      const name = document.createElement('span');
      name.className = 'candidate-name';
      name.textContent = candidate.clientName || '';
      option.append(id, name);

      option.addEventListener('click', () => setSelectedClient(candidate));
      candidateList.appendChild(option);
    });

    candidateSection.hidden = false;
    clientQuery.setAttribute('aria-expanded', 'true');
    setStatus(`${candidates.length} match${candidates.length === 1 ? '' : 'es'}`);
  }

  function renderResult(result) {
    currentResult = result;
    const summary = result.summary;
    summaryBody.replaceChildren();
    positionsBody.replaceChildren();

    const summaryRow = document.createElement('tr');
    [
      summary.clientId || result.clientId || '',
      summary.currency || '',
      formatAmount(summary.marginAvailable),
      formatAmount(summary.totalEquity),
      formatRatio(summary.riskRatio)
    ].forEach((value) => {
      const cell = document.createElement('td');
      cell.textContent = value;
      summaryRow.appendChild(cell);
    });
    summaryBody.appendChild(summaryRow);

    (result.positions || []).forEach((position) => {
      const row = document.createElement('tr');
      [
        position.position || '',
        String(position.totalLong || 0),
        String(position.totalShort || 0),
        String(position.intradayLong || 0),
        String(position.intradayShort || 0)
      ].forEach((value) => {
        const cell = document.createElement('td');
        cell.textContent = value;
        row.appendChild(cell);
      });
      positionsBody.appendChild(row);
    });

    const total = (result.positions || []).length;
    positionCount.textContent = total
      ? `Showing 1 to ${total} of ${total} entries`
      : 'Showing 0 to 0 of 0 entries';
    summaryCard.hidden = false;
    positionsCard.hidden = false;
    copyButton.disabled = false;
  }

  async function fetchJson(url) {
    const response = await fetch(url, {headers: {'Accept': 'application/json'}});
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      try {
        const body = await response.json();
        message = body.message || message;
      } catch (ignored) {
        // Keep the status-based message.
      }
      throw new Error(message);
    }
    return response.json();
  }

  async function searchClients(query) {
    const normalized = query.trim();
    if (!normalized) {
      hideCandidates();
      return [];
    }
    selectedClient = null;
    setStatus('Searching');
    const candidates = await fetchJson(`/api/tds/clients?query=${encodeURIComponent(normalized)}`);
    renderCandidates(candidates);
    return candidates;
  }

  async function queryClient(clientId) {
    setStatus('Loading');
    const result = await fetchJson(`/api/tds/clients/${encodeURIComponent(clientId)}`);
    renderResult(result);
    setStatus('');
  }

  async function copyResult() {
    if (!currentResult) {
      return;
    }
    const rows = buildClipboardRows(currentResult);
    const tsv = rowsToTsv(rows);
    const html = rowsToHtml(rows);
    if (navigator.clipboard && window.ClipboardItem) {
      await navigator.clipboard.write([
        new ClipboardItem({
          'text/plain': new Blob([tsv], {type: 'text/plain'}),
          'text/html': new Blob([html], {type: 'text/html'})
        })
      ]);
    } else if (navigator.clipboard) {
      await navigator.clipboard.writeText(tsv);
    }
    setStatus('Copied');
  }

  function scheduleLookup() {
    window.clearTimeout(searchTimer);
    const query = clientQuery.value.trim();
    selectedClient = null;
    if (query.length < 2) {
      hideCandidates();
      setStatus('');
      return;
    }
    searchTimer = window.setTimeout(async () => {
      try {
        await searchClients(query);
      } catch (error) {
        setStatus(error.message);
      }
    }, 250);
  }

  searchForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      if (selectedClient) {
        await queryClient(selectedClient.clientId);
        return;
      }
      const query = clientQuery.value.trim();
      if (!query) {
        setStatus('Client is required');
        return;
      }
      const candidates = await searchClients(query);
      if (candidates.length === 1) {
        setSelectedClient(candidates[0]);
        await queryClient(candidates[0].clientId);
      }
    } catch (error) {
      setStatus(error.message);
    }
  });

  clientQuery.addEventListener('input', scheduleLookup);

  clientQuery.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') {
      return;
    }
    const firstOption = candidateList.querySelector('.candidate-option');
    if (!selectedClient && firstOption && !candidateSection.hidden) {
      event.preventDefault();
      firstOption.click();
    }
  });

  document.addEventListener('click', (event) => {
    if (!candidateSection.contains(event.target) && event.target !== clientQuery) {
      hideCandidates();
    }
  });

  resetButton.addEventListener('click', resetPage);

  copyButton.addEventListener('click', async () => {
    try {
      await copyResult();
    } catch (error) {
      setStatus(error.message);
    }
  });

  window.tdsClientPage = {
    buildClipboardRows,
    formatAmount,
    formatRatio,
    rowsToTsv,
    rowsToHtml
  };
})();
