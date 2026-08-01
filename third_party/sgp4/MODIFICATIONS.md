# 修改记录

`TLE.java` 的 epoch 解析改为给 `GregorianCalendar` 显式传入 GMT，移除上游修改 JVM
全局默认时区的副作用；SGP4 数值算法、常量和传播流程未修改。

FT8CN 的 TLE 输入校验、TEME 到站心坐标转换、过境搜索、转发器 Doppler、缓存和 Compose 页面均位于 `com.bg7yoz.ft8cn.satellite` 或 `feature.satellite`，不混入上游源码。

Look4Sat 只用于公开功能范围参考，没有复制其 GPL-3.0 轨道、界面或资源源码。
