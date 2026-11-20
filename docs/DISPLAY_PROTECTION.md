# 常亮与防烧屏

长按主面板进入 MCPE，找到「屏幕」：

- **屏幕常亮**：默认开启，沿用已有常亮选择；通过本 Activity 的 KEEP_SCREEN_ON 标志生效。关闭后恢复系统原有超时，不修改全局超时、亮度或其他应用。
- **防烧屏位移**：默认开启，可独立关闭；仅作用于主面板，设置页不位移。参考本地 mijiapanel 的 BurnInShiftController：每 180 秒移动一次，800 毫秒过渡，在 X/Y 各 -16 到 +16 的物理像素范围、4 像素步长中轮换。

81 个坐标打乱后逐一使用，再进入下一轮；相邻位置不重复。时钟、状态、网速、图标、开关和原桌面手势区域一起移动，点击位置随 Compose 布局移动。边缘预留 16 物理像素，背景保持填满屏幕，横竖屏均不裁切。

只在 RESUMED 且窗口有焦点、屏幕可交互时推进位置；进入后台取消周期并复位，恢复前台重新计时。关闭防烧屏、进入设置或重建 Activity 后回到原位。此位移旨在分散固定画面，不能保证消除 OLED 的长期老化。

配置写入原有设备加密存储，变更只更新常亮/防烧屏字段，不改共享意图。纯 Compose 实现，不依赖 LSPosed 或 Root 授权。

参考代码：`G:/My_Projects/mijiapanel/app/src/main/java/com/daxiaamu/mijiapanel/MijiaPanelModule.java` 中的 BurnInShiftController；没有修改 mijiapanel 项目。
