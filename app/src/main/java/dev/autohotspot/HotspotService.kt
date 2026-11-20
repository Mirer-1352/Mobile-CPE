package dev.autohotspot

import android.app.*
import android.content.*
import android.os.*
import android.hardware.usb.UsbManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class HotspotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var root: RootClient
    private lateinit var prefs: Preferences
    private lateinit var cell: CellMonitor
    private var task: Job? = null
    private var usbConnected = false
    private var usbHost = false
    private var topologyRevision = 0L
    private val topologyChanges = Channel<Unit>(Channel.CONFLATED)
    private var wake: PowerManager.WakeLock? = null
    private val usb = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == "android.hardware.usb.action.USB_STATE") {
                val connected = intent.getBooleanExtra("connected", false)
                val host = intent.getBooleanExtra("host_connected", false)
                if (connected == usbConnected && host == usbHost) return
                usbConnected = connected
                usbHost = host
            }
            topologyRevision++
            topologyChanges.trySend(Unit)
        }
    }
    override fun onCreate() {
        super.onCreate()
        prefs = Preferences.get(this); root = RootClient(this); cell = CellMonitor(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watchdog", "共享守护", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(this, "watchdog").setSmallIcon(dev.autohotspot.R.drawable.ic_router)
            .setContentTitle("手机CPE").setContentText("共享守护运行中")
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, notice)
        val usbEvents = IntentFilter("android.hardware.usb.action.USB_STATE").apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usb, usbEvents, RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usb, usbEvents)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autohotspot:watchdog")
        Router.update { it.copy(running = true) }
        FrameworkGuardian.service(this, true)
        scope.launch {
            prefs.state.map { it.sharingPolicy() }.distinctUntilChanged().collect { topologyChanges.trySend(Unit) }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.read().enabled) { stopSelf(); return START_NOT_STICKY }
        if (task == null) task = scope.launch { monitor() }
        return START_STICKY
    }
    private suspend fun monitor() {
        val meter = RateMeter()
        val reconciler = SharingReconciler()
        while (currentCoroutineContext().isActive && prefs.read().enabled) {
            if (wake?.isHeld != true) wake?.acquire(10 * 60 * 1000L)
            AppUpdater.tick(this@HotspotService)
            runCatching { cell.bind() }.onFailure { Router.event("蜂窝信息不可用：${it.message}", updateStatus = false) }
            try {
                val observed = Snapshot.parse(root.request("snapshot"))
                val snapshot = observed.copy(usbHost = observed.usbHostOnly(usbHost))
                val now = SystemClock.elapsedRealtime()
                val rates = meter.sample(snapshot.nets, now)
                val currentUsb = snapshot.usbReady(usbConnected, usbHost)
                val restored = !Router.state.value.root
                val activeChanged = snapshot.active != Router.state.value.active
                Router.update { it.copy(root = true, fresh = true, active = snapshot.active,
                    clients = snapshot.clients, down = rates.first, up = rates.second,
                    ethernetSupported = snapshot.ethernetSupported, usbConnected = currentUsb,
                    ethernetConnected = snapshot.ethernetConnected) }
                if (restored) Router.event("Root 已授权，状态监控中")
                if (activeChanged) Router.event("实际共享：" + snapshot.active.joinToString { it.label }.ifEmpty { "全部关闭" })
                val plan = reconciler.plan(prefs.read().sharingPolicy(), snapshot, currentUsb, topologyRevision, now)
                try {
                    if (plan.completedStops.isNotEmpty()) prefs.update { current ->
                        current.copy(pendingStops = current.pendingStops - (plan.completedStops - current.desired))
                    }
                } catch (error: Exception) { Router.event("关闭确认保存失败：${error.message}") }
                for (command in plan.commands) {
                    // Intent can change while the preceding Root command is suspended.
                    if (!command.appliesTo(prefs.read().sharingPolicy())) continue
                    reconciler.submitted(command, SystemClock.elapsedRealtime())
                    try {
                        root.request(if (command.start) "start" else "stop", command.mode.type)
                        reconciler.accepted(command, SystemClock.elapsedRealtime())
                        Router.event(if (command.start) "已请求开启${command.mode.label}，正在确认接口" else "已请求关闭${command.mode.label}")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (rejected: RootOperationException) { Router.event("${command.mode.label}：${rejected.message}") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Router.update { it.copy(root = false, fresh = false, down = null, up = null, clients = null) }
                Router.event("Root 通道失败：${e.message ?: e.javaClass.simpleName}")
                delay(12000)
            }
            withTimeoutOrNull(3000) { topologyChanges.receive() }
        }
        stopSelf()
    }
    override fun onDestroy() {
        task?.cancel(); scope.cancel(); topologyChanges.close(); root.close(); cell.close()
        runCatching { unregisterReceiver(usb) }
        if (wake?.isHeld == true) wake?.release()
        Router.update { it.copy(running = false, root = false, fresh = false, down = null, up = null,
            status = "守护已暂停，当前系统共享保持不变") }
        FrameworkGuardian.service(this, false)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object {
        fun start(context: Context) {
            FrameworkGuardian.publish(context, arm = true)
            context.startForegroundService(Intent(context, HotspotService::class.java))
        }
    }
}
