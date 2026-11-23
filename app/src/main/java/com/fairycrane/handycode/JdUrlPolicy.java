package com.fairycrane.handycode;
import java.net.URI;
import java.util.Locale;
/** HTTPS-only pickup platform navigation policies (JD, SF Express, Douyin). */
public final class JdUrlPolicy {
 private JdUrlPolicy() {}
 public static final String ORDERS="https://trade.m.jd.com/order/orderlist_jdm.shtml";
 public static final String LOGIN="https://plogin.m.jd.com/login/login?appid=100&returnurl=https%3A%2F%2Ftrade.m.jd.com%2Forder%2Forderlist_jdm.shtml";
 public static final String IDENTITY="https://convenient-h5.jd.com/identityCode?channel=xueshengjirukou";
 public static final String SF_ORDERS="https://www.sf-express.com/chn/sc/waybill/list";
 public static final String SF_QUERY="https://www.sf-express.com/mobile/cn/sc/dynamic_functions/waybill/search?noHead=true&noFooter=true";
 public static final String JD_QUERY="https://www.jdl.com/orderSearch/";
 public static final String JDL_ORDERS="https://www.jdl.com/search/orderList/";
 public static final String DY_ORDERS="https://haohuo.jinritemai.com/views/neworder/center";
 public static final String DY_LOGIN="https://www.douyin.com/user/self";
 private static final String[] JD_HOSTS={"jd.com","3.cn","360buyimg.com","jdpay.com","jingdong.com","jdh.com","jkcsjd.com","jdl.cn","jdl.com","jdcloud-oss.com","jdfcloud.com"};
 private static final String[] DY_HOSTS={"jinritemai.com","snssdk.com","douyin.com","toutiao.com","ecombdstatic.com","douyinstatic.com","byteimg.com","bytescm.com","ibytedapm.com","zijieapi.com","bytedance.com","yhgfb-cn-static.com","ibytedtos.com","bytetos.com","bytegoofy.com","bytegecko.com","bytednsdoc.com","pstatp.com","amemv.com","toutiaoapi.com","zijiecdn.com"};

 private static String validHttpsHost(String raw) {
  if(raw==null||raw.isEmpty()||raw.length()>8192)return null;
  for(int i=0;i<raw.length();i++)if(Character.isWhitespace(raw.charAt(i))||Character.isISOControl(raw.charAt(i))||raw.charAt(i)=='\\')return null;
  try {
   URI u=new URI(raw);String h=u.getHost();
   if(!"https".equalsIgnoreCase(u.getScheme())||u.getRawUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443)||h==null)return null;
   return h.toLowerCase(Locale.ROOT);
  }catch(Exception invalid){return null;}
 }

 public static boolean allowed(String raw) {
  String h=validHttpsHost(raw);
  if(h==null)return false;
  for(String root:JD_HOSTS)if(h.equals(root)||h.endsWith("."+root))return true;
  return false;
 }

 public static boolean allowedMall(String raw) {
  String host=validHttpsHost(raw);
  return host!=null&&(host.equals("jd.com")||host.endsWith(".jd.com"));
 }

 public static boolean isIdentity(String raw) {
  return "convenient-h5.jd.com".equals(validHttpsHost(raw))&&"/identityCode".equals(URI.create(raw).getPath());
 }

 public static boolean isMallLoginPage(String raw) {
  if (!allowedMall(raw)) return false;
  URI uri=URI.create(raw);
  String host=uri.getHost().toLowerCase(Locale.ROOT);
  return "plogin.m.jd.com".equals(host)||"passport.jd.com".equals(host)
   || uri.getPath().matches("(?i).*/login(?:[/.].*)?");
 }

 public static boolean allowedJdTracking(String raw) {
  String h=validHttpsHost(raw);
  return "www.jdl.com".equals(h) || "jdl.com".equals(h)
      || "www.jdl.cn".equals(h) || "jdl.cn".equals(h);
 }

 public static boolean allowedJdlOrders(String raw) {
  String host = validHttpsHost(raw);
  return "www.jdl.com".equals(host) || "i.jdl.com".equals(host)
      || "sso.jdl.com".equals(host) || "passport.jdl.com".equals(host)
      || "passport.jd.com".equals(host) || "plogin.m.jd.com".equals(host);
 }

 public static boolean isDyLoginPage(String raw) {
  String h=validHttpsHost(raw);
  if (h==null) return false;
  if ("douyin.com".equals(h) || h.endsWith(".douyin.com")) return true;
  String path=URI.create(raw).getPath();
  return allowedDy(raw) && path!=null && (path.startsWith("/passport/") || path.startsWith("/login"));
 }

 public static boolean allowedSf(String raw) {
  String h=validHttpsHost(raw);
  return h!=null&&(h.equals("sf-express.com")||h.endsWith(".sf-express.com"));
 }

 public static boolean allowedDy(String raw) {
  String h=validHttpsHost(raw);
  if(h==null)return false;
  for(String root:DY_HOSTS)if(h.equals(root)||h.endsWith("."+root))return true;
  return false;
 }
}
