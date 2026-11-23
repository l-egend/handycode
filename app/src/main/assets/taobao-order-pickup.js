(function () {
 'use strict';
 if (window.top !== window || location.protocol !== 'https:' || location.port || location.username || location.password
     || !/(?:^|\.)(?:taobao\.com|tmall\.com|cainiao\.com)$/.test(location.hostname)) return null;

 const fragment = location.hash.slice(1);
 const queryStart = fragment.indexOf('?');
 const route = queryStart < 0 ? fragment : fragment.slice(0, queryStart);
 if (/^!?\/order-split\/?$/.test(route)) return null;
 const commonDetail = location.origin === 'https://market.m.taobao.com'
  && location.pathname === '/app/mcn/common-logistics/index.html' && (!route || route === '/');
 const params = new URLSearchParams(location.search);
 if (queryStart >= 0) {
  new URLSearchParams(fragment.slice(queryStart + 1)).forEach((value, key) => params.set(key, value));
 }

 function visible(node) {
  if (!node || !node.getClientRects().length) return false;
  const style = window.getComputedStyle(node);
  return style.visibility === 'visible' && style.opacity !== '0';
 }
 function text(node) {
  return node && visible(node) ? (node.innerText || node.textContent || '').trim() : '';
 }
 function normalize(value) {
  return value.normalize('NFKC').replace(/[‐‑‒–—−]/g, '-');
 }
 const codes = new Map();
 function addCode(value, station) {
  const code = normalize(value).trim().replace(/\s*-\s*/g, '-');
  if (!/^[A-Za-z0-9][A-Za-z0-9-]{1,15}$/.test(code)
      || !/\d/.test(code) || /^\d{11,}$/.test(code)
      || /^20\d\d-\d{1,2}-\d{1,2}$/.test(code)) return;
  if (!codes.has(code)) codes.set(code, {code, name: station.slice(0, 60)});
 }
 const labels = '取件码|取货码|提货码|自提码|取件密码|开箱码';
 const standaloneLabel = new RegExp('^(?:' + labels + ')[\\s:：]*$');
 const valuePattern = '([A-Za-z0-9]+(?:\\s*-\\s*[A-Za-z0-9]+)*)';
 const pickupLabel = new RegExp('(?:' + labels + ')(?:\\s*[为是])?[\\s:\\[【(「『]*'
  + valuePattern + '(?![A-Za-z0-9*×•-])', 'g');
 const pickupCard = new RegExp('^(?:' + labels + ')[:\\s]*' + valuePattern + '\\s*[|│]?\\s*复制$');
 const waybillLabel = '(?:运单(?:号码|号|编号)|物流单号|快递单号)';
 const waybillRow = new RegExp('^' + waybillLabel + '[:\\s]*([A-Za-z0-9_-]{8,40})(?=$|[\\s|]|复制)');
 const waybillToolbar = /^(?:[\u3400-\u9fff\s]{1,30})?([A-Za-z0-9-]{8,40})\s*\|?\s*复制\s*\|?\s*打电话$/;
 const collectedStatus = /^(?:已取件|已领取|已出库|本人(?:已)?签收|收件人(?:本人)?已签收|已退回|退回中)/;
 const trackingStatus = /^(待取件|待领取|已入站|驿站代收|已取件|已领取|已出库|已签收|本人签收|已退回|退回中)(?=$|[\s\d])/;
 function readLabeledCodes(description) {
  for (const match of normalize(description).matchAll(pickupLabel)) addCode(match[1], '');
 }
 function nearbyValue(label, pattern) {
  for (let node = label, depth = 0; node && depth < 4; node = node.parentElement, depth++) {
   const line = text(node);
   if (line.length > 140) break;
   const match = normalize(line).match(pattern);
   if (match) return match[1];
  }
  return '';
 }
 function detailMarkers() {
  const markers = {status:'', more:false, codeLabels:[], waybill:'', toolbarWaybill:''};
  for (const node of document.querySelectorAll('a,b,button,div,label,p,span,strong,view,text')) {
   if (node.firstElementChild) continue;
   const content = (node.textContent || '').trim();
   const status = !markers.status && content.match(trackingStatus);
   if (status && visible(node)) markers.status = status[1];
   else if (/^(?:查看更多|收起)物流(?:明细|详情)$/.test(content) && visible(node)) markers.more = true;
   else if (standaloneLabel.test(content) && visible(node)) markers.codeLabels.push(node);
   else if (!markers.waybill && /^(?:运单|物流单号|快递单号)/.test(content) && visible(node)) {
    markers.waybill = nearbyValue(node, waybillRow);
   } else if (!markers.toolbarWaybill && content === '复制' && visible(node)) {
    const candidate = nearbyValue(node, waybillToolbar);
    // A phone number next to a call control cannot identify a parcel.
    if (/\d/.test(candidate) && !/^(?:1[3-9]\d{9}|0\d{2,3}-?\d{7,8})$/.test(candidate)) {
     markers.toolbarWaybill = candidate;
    }
   }
  }
  return markers;
 }

 // The official timeline is newest first. Highlighted values share the phone
 // style with contact numbers, so read them only in the context of a label.
 const updates = Array.from(document.querySelectorAll('[class*="logistics--normal--"]')).filter(visible).map(row => ({
  status: text(row.querySelector('[class*="logistics--status--"]')),
  message: Array.from(row.querySelectorAll('[class*="logistics--dark--"],[class*="logistics--desc--"],[class*="logistics--phone--"]'))
   .map(text).filter(Boolean).join(' ')
 }));
 function collected(update) {
  return collectedStatus.test(update.status)
   || /^已签收/.test(update.status) && /签收人\s*[：:]\s*本人(?:签收)?(?=$|[\s，。；,.;])/.test(update.message);
 }
 const cardNodes = document.querySelectorAll('[class*="pickup-service-card--container--"]');
 const companyWaybill = text(document.querySelector('[class*="company-info--mail--"]'));
 const trade = ['tradeId', 'orderId', 'order_id'].map(key => params.get(key) || '').find(id => /^\d{1,32}$/.test(id));
 const waybill = ['mailNo', 'waybillNo'].map(key => params.get(key) || '').find(id => /^[A-Za-z0-9_-]{1,80}$/.test(id));
 const logisticsOrder = params.get('orderCode') || '';
 const parcelIndex = params.get('selectedIndex') || '0';
 const index = /^\d{1,4}$/.test(parcelIndex) ? parcelIndex : '0';
 const validWaybill = value => /^(?=.*\d)[A-Za-z0-9_-]{8,40}$/.test(value);
 const hasUrlId = trade || waybill || /^[A-Za-z0-9_-]{1,80}$/.test(logisticsOrder);
 const latest = updates[0];
 const layout = !commonDetail || !hasUrlId || !latest?.status ? detailMarkers() : null;
 const visibleWaybill = [companyWaybill, layout?.waybill, layout?.toolbarWaybill].find(value => value && validWaybill(value));

 // The order dialog also loads login and order-list pages. Outside the known
 // detail URL, require a timeline and carrier header or the visible detail UI.
 if (!commonDetail && !(updates.length && (cardNodes.length || validWaybill(companyWaybill)))
     && !(layout.status && (layout.more || (visibleWaybill && layout.codeLabels.length)))) return null;
 // Split-order navigation retains the trade ID and changes the parcel index.
 const orderKey = trade ? 'trade:' + trade + ':' + index
  : waybill ? 'mail:' + waybill + ':' + index
  : /^[A-Za-z0-9_-]{1,80}$/.test(logisticsOrder) ? 'logistics:' + logisticsOrder + ':' + index
  : visibleWaybill ? 'mail:' + visibleWaybill + ':0' : '';
 const pickedUp = latest?.status ? collected(latest) : collectedStatus.test(layout?.status || '');
 if (pickedUp && orderKey) {
  return {href:location.href, orderKey, pickedUp:true, codes:[]};
 }
 cardNodes.forEach(card => {
  const label = text(card.querySelector('[class*="pickup-service-card--codeLabel--"]'));
  if (label !== '取件码') return;
  addCode(text(card.querySelector('[class*="pickup-service-card--code--"]')),
   text(card.querySelector('[class*="pickup-service-card--pointName--"]')));
 });
 // The standard logistics card has a separate description and value.
 document.querySelectorAll('[class*="important-info-cnt--ud--"],[class*="important-info-cnt--lr--"]').forEach(card => {
  const label = text(card.querySelector('[class*="important-info-cnt--desc--"]'));
  const value = text(card.querySelector('[class*="important-info-cnt--info--"]'));
  if (standaloneLabel.test(label)) addCode(value, '');
  else if (standaloneLabel.test(value)) addCode(label, '');
  else readLabeledCodes(label + ' ' + value);
 });

 // A later tracking update can omit the code. Use the newest visible message
 // that includes one, stopping before any confirmed collection or return.
 for (const update of updates) {
  if (collected(update)) break;
  readLabeledCodes(update.message);
  if (codes.size) break;
 }
 if (!codes.size) {
  const detail = layout || detailMarkers();
  if (detail.status || updates.length) {
   for (const label of detail.codeLabels) addCode(nearbyValue(label, pickupCard), '');
  }
 }
 // A visible pickup card is useful even when this detail variant omits the
 // parcel identifier. The app can store that code locally without an order ID.
 if (!orderKey && !(codes.size && (layout?.status || latest?.status))) return null;
 return {href:location.href, orderKey, pickedUp, codes:Array.from(codes.values()).slice(0, 12)};
})()
