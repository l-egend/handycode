(function () {
 'use strict';
 if (window.top !== window || location.protocol !== 'https:' || location.port
     || location.username || location.password || !/(^|\.)jd\.com$/i.test(location.hostname)) return null;
 function loginPage() {
  return /^(?:plogin\.m|passport)\.jd\.com$/.test(location.hostname) || /\/login(?:\/|\.|$)/i.test(location.pathname);
 }
 if (loginPage()) return {href:location.href,login:true,problem:'',ready:false,orders:[]};
 if (location.hostname !== 'trade.m.jd.com' && location.hostname !== 'my.m.jd.com') return null;
 if (!window.HandyJdOrders) {
  var state={captured:false,login:false,problem:'',orders:[]};
  function clean(value,max){return typeof value==='string'?value.slice(0,max):'';}
  function pickupCode(text, atStation) {
   var normalized=text.normalize('NFKC').replace(/[‐‑‒–—−]/g,'-').replace(/\s*-\s*/g,'-');
   var expressions=[
    /(?:取件码|提货码|取货码|凭码|自提码|取件密码|自提密码|开箱码|钥匙码|入库码|包裹码)\s*(?:为|是)?[:\s【\[(「]*([A-Za-z0-9][A-Za-z0-9-]{2,15})(?![A-Za-z0-9-*])/g,
    /(?:请凭|凭)[:\s【\[(「]*([A-Za-z0-9][A-Za-z0-9-]{3,13})[】\])」\s]*(?:至|到|前往|取件|领取)/g
   ];
   if(atStation)expressions.push(/(?:^|[^\dA-Za-z-])([A-Za-z0-9]{1,4}-\d{1,3}-\d{2,6})(?![A-Za-z0-9-])/g);
   for(var i=0;i<expressions.length;i++){
    var match;
    while((match=expressions[i].exec(normalized))){
     var value=match[1];
     if(/\d/.test(value)&&!/-$/.test(value)&&!/^\d{11,}$/.test(value)
        &&!/^20\d\d-\d{1,2}-\d{1,2}$/.test(value))return value;
    }
   }
   return '';
  }
  function buildPromptText(promptInfo){
   if(!promptInfo||typeof promptInfo!=='object')return '';
   var raw=clean(promptInfo.content,600);
   var vars=Array.isArray(promptInfo.variableList)?promptInfo.variableList.slice(0,32):[];
   if(!raw&&!vars.length)return '';
   var idx=0;
   var merged=raw.replace(/%%%/g,function(){
    var v=idx<vars.length?clean(String(vars[idx++]||''),80):'';
    return ' '+v+' ';
   });
   if(idx<vars.length){
    for(var i=idx;i<vars.length;i++)merged+=' '+clean(String(vars[i]||''),80);
   }
   return merged.trim();
  }
  function jdUrl(raw){
   if(typeof raw!=='string'||!raw)return '';
   try{
    var u=new URL(raw,location.href);
    if(u.protocol==='https:'&&!u.port&&!u.username&&!u.password&&/(^|\.)jd\.com$/i.test(u.hostname))return u;
   }catch(ignore){}
   return null;
  }
  function jdLink(raw){
   var u=jdUrl(raw);
   return u&&u.href.length<=500?u.href:'';
  }
  function extractStation(detail){
   var s=String(detail||'').replace(/\s+/g,' ').trim();
   var loc=s.match(/【([^】]{2,40}?(?:驿站|自提点|代收点|快递柜|自提柜|丰巢|门卫|超市|菜鸟)[^】]{0,20})】/);
   return loc?loc[1]:'';
  }
  function shortSummary(detail,tip){
   var s=String(detail||'').replace(/\s+/g,' ').trim();
   var loc=extractStation(s);
   if(loc)return '已派送至 '+loc+(tip?' · '+tip:'');
   var brief=s.replace(/如有疑问请电联.*$/,'').replace(/投诉电话.*$/,'').replace(/有事呼叫我.*$/,'').trim();
   if(brief.length>48)brief=brief.slice(0,48)+'…';
   return brief||(tip||'');
  }
  function orderRow(id,name,status,description,tip,link) {
   if(/取消|关闭|退款|已退回|退回中|^(?:已完成|交易完成|订单完成|已取件|已领取|已提货|本人已签收|收件人本人已签收)/.test(status))return null;
   var atStation=/待取|待自提|待提货/.test(status)
    || /(?:已派送至|已投递|已放入|已存放在|已到达|已由|代收).{0,60}(?:驿站|自提点|代收点|快递柜|自提柜|丰巢|菜鸟)|请您?凭(?:取件码|提货码|取货码|自提码)/.test(description);
   if(/^已签收/.test(status)&&!atStation)return null;
   var code=pickupCode(description,atStation);
   return {id:id,name:name,station:extractStation(description),status:status,
    detail:shortSummary(description,tip),code:code,arrived:!!code||atStation,link:link};
  }
  function consumeList(list, fromFloor) {
   var rows=[],seen=Object.create(null);
   list.slice(0,100).forEach(function(o){
    if(!o||(fromFloor&&String(o.type)==='1'))return;
    var id=String(o.orderId||'').slice(0,80);
    if(id&&seen[id])return;
    if(!id&&!fromFloor)return;
    var p=o.progressInfo||{};
    var fields=[clean(p.content,1500),buildPromptText(o.promptInfo),clean(p.tip,500),
     clean(p.title,200),clean(p.subContent,500)];
    var goods=Array.isArray(o.wareInfoList)?o.wareInfoList[0]:null;
    var name=clean(goods&&goods.wareName,180)||clean(o.wareName,180)||'京东订单';
    var status=clean(fromFloor?o.orderStatusText:(o.orderStatusInfo&&o.orderStatusInfo.orderStatusName),100);
    var description=fromFloor?clean(o.deliveryInfo,1500):fields.filter(Boolean).join(' ');
    var row=orderRow(id,name,status,(description+' '+status).trim(),clean(p.tip,500),
     jdLink(p.progressLink)||jdLink(o.orderDetailLink));
    if(!row||(!id&&!row.code))return;
    var key=id||'pickup:'+row.station+':'+row.code;
    if(seen[key])return;
    seen[key]=true;
    rows.push(row);
   });
   state.orders=rows;state.captured=true;state.login=false;state.problem='';
  }
  function orderResponseUrl(raw) {
   var u=jdUrl(raw);
   if(!u||!['trade.m.jd.com','api.m.jd.com','me-api.jd.com'].includes(u.hostname))return null;
   return u;
  }
  function consume(root,u) {
   if(!root||typeof root!=='object')return;
   var body=root.body&&typeof root.body==='object'?root.body:root;
   var list=Array.isArray(body.orderList)?body.orderList:root.orderList;
   var floor=body.orderFloorInfos;
   var orderRequest=/order/i.test(u.pathname)||/^(?:order[_-]?list|GetOrderFloorInfo)/i.test(u.searchParams.get('functionId')||'');
   if(orderRequest){
    var error=String(body.errorCode||'');
    var reason=clean(body.errorReason||body.errMsg||body.msg||root.msg,180);
    if(error==='302'||String(root.code)==='3'||root.notLogin===true||/未登录|请先登录/.test(reason)){
     state.login=true;state.captured=false;state.problem='';state.orders=[];return;
    }
    if(error==='601'||/大促异常火爆|快速通道|服务繁忙/.test(reason)){state.problem='busy';return;}
    if(error&&error!=='0'){state.problem='unavailable';return;}
   }
   if(Array.isArray(list))consumeList(list,false);
   else if(Array.isArray(floor))consumeList(floor,true);
  }
  function response(text,url) {
   if(typeof text!=='string'||text.length>2097152)return;
   try{consume(JSON.parse(text),url);}catch(ignore){}
  }
  function readEmbeddedOrders() {
   if(state.captured||state.login||state.problem)return;
   var list=window.orderListData;
   if(typeof list==='string'){
    if(list.length>2097152)return;
    try{list=JSON.parse(list);}catch(ignore){return;}
   }
   if(!Array.isArray(list))return;
   var rows=[];
   list.slice(0,100).forEach(function(item){
    if(Array.isArray(item))item.slice(0,100-rows.length).forEach(function(order){rows.push(order);});
    else if(item&&rows.length<100)rows.push(item);
   });
   if(rows.some(function(order){return order&&order.orderId;}))consumeList(rows,false);
  }
  var send=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send=function(){
   this.addEventListener('load',function(){
    try{
     var url=orderResponseUrl(this.responseURL);
     if(!url)return;
     if(this.responseType==='json')consume(this.response,url);
     else if(!this.responseType||this.responseType==='text')response(this.responseText,url);
    }catch(ignore){}
   },{once:true});
   return send.apply(this,arguments);
  };
  var fetchOriginal=window.fetch;
  if(fetchOriginal)window.fetch=function(){
   var promise=fetchOriginal.apply(this,arguments);
   promise.then(function(res){
    try{
     var url=orderResponseUrl(res.url);
     if(url)res.clone().text().then(function(text){response(text,url);}).catch(function(){});
    }catch(ignore){}
   }).catch(function(){});
   return promise;
  };
  // Fallback only extracts bounded order blocks; never reads login form values.
  function domOrders(){
   var nodes=document.querySelectorAll('a[href*="orderId"],a[href*="orderid"],[class*="my_logistics_item"][data-id]');
   var rows=[],seen=Object.create(null);
   for(var i=0;i<nodes.length&&rows.length<50;i++){
    var node=nodes[i],url=jdUrl(node.href);
    var id=url?(url.searchParams.get('orderId')||url.searchParams.get('orderid')):node.getAttribute('data-id');
    if(!id||seen[id])continue;
    var block=node.closest('li,[class*="order-item"],[class*="orderItem"],[class*="order_item"],[class*="order_box"],[class*="my_logistics_item"]');
    if(!block)continue;
    var text=clean(block.innerText,2000);
    if(/已取消|订单关闭|退款成功|已取件|已领取|已完成/.test(text))continue;
    var row=orderRow(id.slice(0,80),'京东待取订单','',text,'',jdLink(node.href));
    if(!row||!row.arrived)continue;
    seen[id]=true;rows.push(row);
   }
   return rows;
  }
  window.HandyJdOrders={snapshot:function(){
   readEmbeddedOrders();
   var bodyText=clean(document.body&&document.body.innerText,3000).replace(/\s/g,'');
   var login=loginPage()||state.login||/欢迎逛京东.{0,80}立即登录/.test(bodyText);
   var problem=state.problem,empty=false;
   var notice=!login&&document.querySelector('[class*="index-module__errorCon"]');
   if(notice&&notice.getClientRects().length){
    var message=(notice.innerText||'').slice(0,500).replace(/\s/g,'');
    empty=/(?:暂无|还没有|没有|未找到|暂未查询到)(?:符合条件的|相关的?|任何)?(?:订单|包裹)|(?:订单|包裹)空空如也/.test(message)
     && !/异常|失败|繁忙|通道|重试|再试/.test(message);
    if(!empty&&/大促异常火爆|快速通道|服务繁忙/.test(message))problem='busy';
    else if(!empty&&/异常|失败|出错|错误|无法加载|重新加载|重试|再试/.test(message))problem='unavailable';
   }
   if(!state.captured&&!problem&&/大促异常火爆|服务繁忙/.test(bodyText))problem='busy';
   if(!state.captured&&!problem&&/账号信息获取失败|账户信息获取失败|网络请求失败/.test(bodyText))problem='unavailable';
   if(!state.captured&&!problem&&/暂时没有相关订单|暂无相关订单/.test(bodyText))empty=true;
   var rows=login||problem||empty?[]:(state.captured?state.orders:domOrders());
   return {href:location.href,login:login,problem:problem,ready:!login&&!problem&&(state.captured||rows.length>0||empty),orders:login||problem||empty?[]:rows};
  }};
 }
 return window.HandyJdOrders.snapshot();
})()
