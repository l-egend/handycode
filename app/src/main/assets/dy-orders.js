(function () {
 'use strict';
 if (location.protocol !== 'https:' || !/(^|\.)(jinritemai|snssdk|douyin|toutiao)\.com$/i.test(location.hostname) || window.top !== window) return null;
 function isDouyinWebHost(){
  return /(^|\.)douyin\.com$/i.test(location.hostname);
 }
 if (!window.HandyDyOrders) {
  var state={captured:false,authExpired:false,login:false,needSso:false,ssoSuccess:false,error:'',orders:[],byId:Object.create(null),probed:Object.create(null),revision:0};
  function clean(value,max){return typeof value==='string'?value.slice(0,max):'';}
  function code(text) {
   var s=String(text||'');
   var m=s.match(/(?:\u53d6\u4ef6\u7801|\u63d0\u8d27\u7801|\u53d6\u8d27\u7801|\u51ed\u7801|\u81ea\u63d0\u7801|\u53d6\u4ef6\u5bc6\u7801|\u5f00\u7bb1\u7801|\u94a5\u5319\u7801)[:\uff1a\s\u3010\[\uff08(]*([A-Za-z0-9-]{3,16})(?![A-Za-z0-9-])/);
   if(m)return m[1];
   var shelf=s.match(/(?:^|[^\dA-Za-z-])([A-Za-z0-9]{1,4}-\d{1,3}-\d{2,6})(?![A-Za-z0-9-])/);
   if(shelf&&!/^20\d\d-\d{1,2}-\d{1,2}$/.test(shelf[1]))return shelf[1];
   return '';
  }
  function hasLocalDyCookie(){
   try{
    return /(?:^|;\s*)(?:sessionid|sessionid_ss|sid_tt|sid_ucp_v1|ssid_ucp_v1|sid_guard)=([^;]+)/.test(document.cookie||'');
   }catch(ignore){
    return false;
   }
  }
  function markSsoLoginSuccess(){
   if(state.ssoSuccess)return;
   state.ssoSuccess=true;
   state.authExpired=false;
   state.error='';
   state.login=false;
   state.needSso=false;
   try{console.info('__HANDY_DY_LOGIN_SUCCESS__');}catch(ignore){}
  }
  function upsert(id,name,status,detail,c){
   if(!id)return;
   id=String(id).slice(0,80);
   var item=state.byId[id];
   if(!item){
    item={id:id,name:name||'\u6296\u97f3\u8ba2\u5355',status:status||'\u8fd0\u8f93\u4e2d',detail:detail||'',code:c||''};
    state.byId[id]=item;state.orders.push(item);
   }else{
    if(name)item.name=name;
    if(status)item.status=status;
    if(detail)item.detail=detail;
    if(c)item.code=c;
   }
   state.revision++;
  }
  function probeExpress(id){
   if(!id||state.probed[id]||typeof window.fetch!=='function')return;
   state.probed[id]=true;
   window.fetch('https://ec.snssdk.com/logistics/ajaxindex?enter_from=order_list&id='+encodeURIComponent(id),{credentials:'include'}).catch(function(){});
  }
  function visibleLoginPanel(){
   if(!isDouyinWebHost())return null;
   var panels=document.querySelectorAll('#douyin_login_comp_h5_panel,#douyin_login_comp_flat_panel,#login-panel-new');
   for(var i=0;i<panels.length;i++){
    var panel=panels[i];
    if(!panel.getClientRects().length)continue;
    var bounds=panel.getBoundingClientRect();
    if(bounds.width>60&&bounds.height>60)return panel;
   }
   return null;
  }
  function smsLoginControl(){
   if(location.origin!=='https://www.douyin.com')return null;
   var panel=visibleLoginPanel();
   if(!panel)return null;
   var controls=panel.querySelectorAll('button,[role="tab"],[role="button"],span');
   for(var i=0;i<controls.length;i++){
    var control=controls[i];
    if(!/^(?:手机验证码登录|验证码登录|手机号登录|短信登录)$/.test(control.textContent.trim()))continue;
    if(control.closest('form,button[type="submit"],[disabled],[hidden],[aria-disabled="true"],[aria-hidden="true"]'))continue;
    if(control.getClientRects().length&&window.getComputedStyle(control).visibility==='visible')return control;
   }
   return null;
  }
  function fitLoginPanel(smsControl){
   var panel=smsControl.closest('#login-panel-new');
   if(!panel)return;
   var landing=panel.querySelector('#douyin_login_landing_flat_container');
   if(!landing||!landing.contains(smsControl))return;
   var smsColumn=Array.from(landing.children).find(function(child){return child.contains(smsControl);});
   if(!smsColumn||!landing.querySelector('#douyin_login_comp_scan_code_login_content'))return;
   if(landing.querySelector('[data-hc-dy-sms-column]')!==smsColumn){
    var previous=landing.querySelector('[data-hc-dy-sms-column]');
    if(previous)previous.removeAttribute('data-hc-dy-sms-column');
    smsColumn.setAttribute('data-hc-dy-sms-column','');
    for(var shell=landing.parentElement;shell&&shell!==panel;shell=shell.parentElement){
     shell.setAttribute('data-hc-dy-login-shell','');
    }
   }
   if(document.getElementById('hc-dy-login-layout'))return;
   var style=document.createElement('style');
   style.id='hc-dy-login-layout';
   style.textContent=`
@media (max-width: 800px) {
 #login-panel-new { max-width: 100vw !important; }
 #login-panel-new [data-hc-dy-login-shell], #login-panel-new #douyin_login_comp_flat_panel_header {
  box-sizing: border-box;
  width: 100% !important;
  min-width: 0 !important;
  max-width: 100% !important;
 }
 #login-panel-new #douyin-login-new-id { max-height: 100vh; overflow-y: auto; }
 #login-panel-new #douyin_login_comp_flat_panel_title { max-width: 100%; box-sizing: border-box; }
 #login-panel-new #douyin_login_landing_flat_container {
  box-sizing: border-box;
  width: 100% !important;
  min-width: 0 !important;
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  padding: 12px !important;
  gap: 20px;
 }
 #login-panel-new #douyin_login_landing_flat_container > * {
  box-sizing: border-box;
  min-width: 0 !important;
  max-width: 100% !important;
  flex: none !important;
  border-inline: 0 !important;
  margin-inline: 0 !important;
 }
 #login-panel-new [data-hc-dy-sms-column] { order: -1; }
}
`;
   document.head.appendChild(style);
  }
  function isLoggedProfileVisibleOnDouyin(){
   if(!isDouyinWebHost()||visibleLoginPanel())return false;
   var uInfo=document.querySelector('[data-e2e="user-info"]');
   var txt=clean((uInfo?uInfo.innerText:(document.body&&document.body.innerText))||'',4000);
   return /\u6296\u97f3\u53f7[\uff1a:]\s*[A-Za-z0-9_.-]{3,}/.test(txt);
  }
  function isZero(value){return value===0||value==='0';}
  function consume(root,urlStr){
   if(!root||typeof root!=='object')return;
   var u=String(urlStr||'');
   if(/\/passport\/web\/check_qrconnect/i.test(u)){
    var qrData=root.data&&typeof root.data==='object'?root.data:root;
    if((root.message==='success'||isZero(root.error_code))&&qrData&&qrData.status==='confirmed'){
     markSsoLoginSuccess();
    }
    return;
   }
   if(/\/(sms_login|sms_login_continue|quick_login|account_login|user\/login)(\/|\?|$)/i.test(u)){
    var lData=root.data&&typeof root.data==='object'?root.data:null;
    var errorCode=lData&&lData.error_code!=null?lData.error_code:root.error_code;
    if(root.message!=='error'&&(isZero(errorCode)||(errorCode==null&&root.message==='success'))){
     markSsoLoginSuccess();
    }
    return;
   }
   if(/\/aweme\/v1\/web\/user\/profile\/self/i.test(u)){
    if(Number(root.status_code)===0&&root.user&&(root.user.uid||root.user.sec_uid)){
     markSsoLoginSuccess();
    }
    return;
   }
   var url;
   try{url=new URL(u,location.href);}catch(ignore){return;}
   if(url.origin!=='https://ec.snssdk.com')return;
   var isOrderPage=url.pathname==='/order/page'||url.pathname==='/order/page/';
   var isLogistics=url.pathname.indexOf('/logistics/')===0;
   if(!isOrderPage&&!isLogistics)return;
   var codeStr=String(root.code!==undefined?root.code:'');
   var msgStr=String(root.msg||root.message||'');
   if(codeStr==='10001010A'||/\u7528\u6237\u672a\u767b\u5f55|\u8bf7\u767b\u5f55\u540e\u91cd\u8bd5|\u8bf7\u5148\u767b\u5f55/.test(msgStr)){
    // Auxiliary endpoints may reject a request even when the order list is available.
    if(!isOrderPage)return;
    state.authExpired=true;
    state.captured=false;
    state.ssoSuccess=false;
    state.error='';
    state.login=true;
    state.needSso=true;
    state.orders=[];
    state.byId=Object.create(null);
    return;
   }
   if((typeof root.status_code==='number'&&root.status_code!==0)||(typeof root.st==='number'&&root.st!==0)||(root.code!==undefined&&codeStr!=='0')){
    if(isOrderPage&&!state.captured){
     state.error=clean(msgStr||'\u6296\u97f3\u8ba2\u5355\u52a0\u8f7d\u5931\u8d25\uff0c\u8bf7\u70b9\u51fb\u53f3\u4e0a\u89d2\u300c\u5237\u65b0\u7f51\u9875\u300d\u6216\u91cd\u65b0\u767b\u5f55\u91cd\u8bd5\u3002',120);
    }
    return;
   }
   var orderList=Array.isArray(root.data)?root.data:(root.data&&Array.isArray(root.data.list||root.data.orders)?(root.data.list||root.data.orders):null);
   if(isOrderPage&&orderList){
    state.authExpired=false;
    state.error='';
    state.captured=true;state.login=false;state.needSso=false;
    orderList.slice(0,30).forEach(function(entry,idx){
     var o=(entry&&entry.order_info)||entry;
     if(!o)return;
     var id=clean(String(o.order_id||o.shop_order_id||o.id||''),80);
     if(!id)return;
     var status=clean(String(o.status_desc||o.order_status_desc||(o.status_info&&o.status_info.text)||''),60);
     if(/\u53d6\u6d88|\u5173\u95ed|\u9000\u6b3e\u6210\u529f/.test(status))return;
     var sub=(Array.isArray(o.sub_orders)&&o.sub_orders[0])||(Array.isArray(o.product_item)&&o.product_item[0])||(Array.isArray(o.sku_specs)&&o.sku_specs[0])||o;
     var name=clean(String(sub.product_name||sub.sku_name||o.product_name||o.shop_name||'\u6296\u97f3\u5546\u54c1'),120);
     var logCtx='';
     if(o.logistics&&Array.isArray(o.logistics.data)&&o.logistics.data[0]){
      var ld=o.logistics.data[0];
      logCtx=clean((ld.title?ld.title+' ':'')+(ld.context||''),400);
      if(!status&&ld.title)status=clean(String(ld.title),60);
     }
     var detail=clean(String(logCtx||o.logistics_text||o.express_text||(o.logistics_info&&o.logistics_info.desc)||''),400);
     var c=code((o.pick_code?'\u53d6\u4ef6\u7801:'+o.pick_code:'')+' '+detail+' '+status);
     upsert(id,name,status||'\u5f85\u6536\u8d27',detail,c);
     if(idx<12&&(o.logistics||Number(o.final_status)===3||Number(o.order_status)===3||/\u53d1\u8d27|\u8fd0\u8f93|\u6d3e\u9001|\u5f85\u6536\u8d27|\u9a7f\u7ad9|\u4ee3\u6536/.test(status+' '+detail)))probeExpress(id);
    });
    return;
   }
   if(!isLogistics||!state.captured||state.authExpired)return;
   var exp=root.express_info||root.data||root;
   var pick=exp.pick_info||(root.data&&root.data.pick_info)||root.pick_info;
   var routes=Array.isArray(exp.data)?exp.data:(Array.isArray(root.data)?root.data:null);
   if(pick||routes){
    var oid=url.searchParams.get('id')||clean(String(exp.order_id||''),80);
    if(!state.byId[oid])return;
    var pCode=pick?clean(String(pick.pick_code||''),30):'';
    var pSite=pick?clean(String(pick.pick_site||''),120):'';
    var pAddr=pick?clean(String(pick.pick_address||''),300):'';
    var latest='';
    if(routes&&routes.length){
     for(var i=0;i<routes.length;i++){
      var ctx=clean(routes[i]&&(routes[i].context||routes[i].detail)||'',500);
      if(!latest&&ctx)latest=ctx;
      if(!pCode)pCode=code(ctx);
     }
    }
    var fullDetail=[pSite,pAddr,latest].filter(Boolean).join(' \u00b7 ');
    if(pCode||fullDetail){
     upsert(oid,null,pSite||(pCode?'\u5f85\u53d6\u4ef6':null),fullDetail,pCode);
    }
   }
  }
  function response(text,urlStr){
   if(typeof text!=='string'||text.length>2097152)return;
   var trimmed=text.trim();
   if(trimmed.charAt(0)!=='{')return;
   try{consume(JSON.parse(trimmed),urlStr);}catch(ignore){}
  }
  var send=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send=function(){
   this.addEventListener('load',function(){
    try{
     var rawUrl=this.responseURL||location.href;
     var url=new URL(rawUrl,location.href);
     if(url.protocol!=='https:'||!/(^|\.)(jinritemai|snssdk|douyin|toutiao)\.com$/i.test(url.hostname))return;
     if(this.responseType==='json')consume(this.response,url.href);
     else if(!this.responseType||this.responseType==='text')response(this.responseText,url.href);
    }catch(ignore){}
   });
   return send.apply(this,arguments);
  };
  var fetchOriginal=window.fetch;
  if(fetchOriginal)window.fetch=function(){
   var promise=fetchOriginal.apply(this,arguments);
   promise.then(function(res){
    try{
     var url=new URL(res.url,location.href);
     if(url.protocol==='https:'&&/(^|\.)(jinritemai|snssdk|douyin|toutiao)\.com$/i.test(url.hostname))
      res.clone().text().then(function(t){response(t,url.href);}).catch(function(){});
    }catch(ignore){}
   }).catch(function(){});
   return promise;
  };
  function domOrders(){
   if(isDouyinWebHost())return [];
   var nodes=document.querySelectorAll('[class*="order"],[class*="express"],[class*="logistics"],li'),rows=[],seen=Object.create(null);
   for(var i=0;i<nodes.length&&rows.length<40;i++){
    var text=(nodes[i].innerText||'').slice(0,1500),c=code(text);
    if(!c||/\u53d6\u6d88|\u5173\u95ed|\u9000\u6b3e\u6210\u529f/.test(text))continue;
    if(seen[c])continue;
    seen[c]=true;
    rows.push({id:'dy_'+c,name:'\u6296\u97f3\u5f85\u53d6\u5305\u88f9',status:'\u5f85\u53d6\u4ef6',detail:text.replace(/\s+/g,' ').slice(0,400),code:c});
   }
   return rows;
  }
  window.HandyDyOrders={
   ingest:response,
   openSmsLogin:function(){
    var control=smsLoginControl();
    if(!control)return false;
    control.click();
    return true;
   },
   snapshot:function(){
    var isLoginHost=isDouyinWebHost()||(/(^|\.)(snssdk|toutiao)\.com$/i.test(location.hostname)&&/\/passport\/|\/login/i.test(location.pathname));
    if(isLoginHost){
     if(state.ssoSuccess||isLoggedProfileVisibleOnDouyin()){
      markSsoLoginSuccess();
      return {href:location.href,login:false,needSso:false,ssoDone:true,ready:false,orders:[]};
     }
     var smsControl=smsLoginControl();
     if(smsControl)fitLoginPanel(smsControl);
     return {href:location.href,login:true,smsLogin:!!smsControl,needSso:false,ready:false,orders:[]};
    }
    if(state.authExpired){
     return {href:location.href,login:true,needSso:true,ready:false,orders:[]};
    }
    var hasCookie=hasLocalDyCookie();
    var domRows=domOrders();
    var rows=state.orders.length>0?state.orders:domRows;
    var bodyText=clean((document.body&&document.body.innerText)||'',4000);
    if(!state.captured&&!hasCookie&&rows.length===0&&/\u8bf7\u5148\u767b\u5f55|\u4f60\u8fd8\u672a\u767b\u5f55/.test(bodyText)){
     state.login=true;state.needSso=true;
    }
    var login=state.login&&!state.captured&&rows.length===0;
    if(!login&&!state.captured&&rows.length===0&&state.error){
     return {href:location.href,login:false,needSso:false,ready:false,error:state.error,orders:[]};
    }
    return {href:location.href,login:login,needSso:login&&state.needSso,ready:!login&&(state.captured||rows.length>0),orders:login?[]:rows};
   }
  };
 }
 return window.HandyDyOrders.snapshot();
})()
