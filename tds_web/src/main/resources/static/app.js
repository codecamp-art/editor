(function () {
  const searchForm = document.getElementById('searchForm');
  const clientQuery = document.getElementById('clientQuery');
  const status = document.getElementById('status');
  const candidateSection = document.getElementById('candidateSection');
  const candidateList = document.getElementById('candidateList');
  const resultTable = document.getElementById('resultTable');
  const resultBody = document.getElementById('resultBody');
  const copyButton = document.getElementById('copyButton');
  let currentResult = null;

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
    const formatted = number.toLocaleString('en-US', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    });
    return formatted;
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

  function renderResult(result) {
    currentResult = result;
    const rows = buildClipboardRows(result);
    resultBody.replaceChildren();
    rows.forEach((row, rowIndex) => {
      const tr = document.createElement('tr');
      if (rowIndex === 2) {
        tr.className = 'separator';
      }
      row.forEach((cell, columnIndex) => {
        const element = document.createElement(rowIndex === 0 || rowIndex === 3 ? 'th' : 'td');
        element.textContent = cell;
        if (rowIndex !== 0 && rowIndex !== 3 && columnIndex > 0) {
          element.className = 'number';
        }
        tr.appendChild(element);
      });
      resultBody.appendChild(tr);
    });
    resultTable.hidden = false;
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

  function renderCandidates(candidates) {
    candidateList.replaceChildren();
    if (!candidates.length) {
      candidateSection.hidden = true;
      setStatus('No matching clients');
      return;
    }

    candidates.forEach((candidate) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'candidate-button';
      button.textContent = `${candidate.clientId} ${candidate.clientName || ''}`.trim();
      button.addEventListener('click', () => queryClient(candidate.clientId));
      candidateList.appendChild(button);
    });
    candidateSection.hidden = false;
    setStatus(`${candidates.length} match${candidates.length === 1 ? '' : 'es'}`);
  }

  async function searchClients(query) {
    setStatus('Searching');
    candidateSection.hidden = true;
    copyButton.disabled = true;
    const candidates = await fetchJson(`/api/tds/clients?query=${encodeURIComponent(query)}`);
    renderCandidates(candidates);
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

  searchForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const query = clientQuery.value.trim();
    if (!query) {
      setStatus('Client is required');
      return;
    }
    try {
      await searchClients(query);
    } catch (error) {
      setStatus(error.message);
    }
  });

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
