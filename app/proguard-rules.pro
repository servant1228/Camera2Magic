# 1. 保护所有 native 方法不被重命名或删除
-keepclasseswithmembernames class * {
    native <methods>;
}

# com.nothing.camera2magic
-keep class com.nothing.camera2magic.** {
    *;
}
