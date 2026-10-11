package com.dsh.notifbutler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通知管家 —— 无需 root 的通知拦截工具。
 *
 * 工作原理：
 *   通过「通知使用权」读取系统投递的所有通知，
 *   对拦截名单里的 App 立即取消通知。
 *
 * ★ 本文件不使用匿名内部类，也不实现泛型接口 ——
 *   JDK 25 编译出的这类字节码会让 d8 崩溃（见项目说明）。
 */
public class MainActivity extends Activity
        implements AdapterView.OnItemClickListener, View.OnClickListener,
        TextWatcher, DialogInterface.OnClickListener {

    private static final int DLG_BLOCK_ALL = 1;
    private static final int DLG_ALLOW_ALL = 2;

    private static volatile MainActivity sInstance;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ListView listView;
    private TextView status;
    private EditText search;
    private View authBar;
    private View batteryBar;
    private Button btnGrant;
    private Button btnBattery;
    private Button btnBatteryIgnore;
    private Button btnRefresh;
    private Button btnAbout;
    private Button btnClear;
    private Button btnNew;
    private Button btnSystem;
    private Button btnBlocked;
    private Button btnBlockAll;
    private Button btnAllowAll;

    private final List<AppEntry> all = new ArrayList<AppEntry>();
    private final List<AppEntry> shown = new ArrayList<AppEntry>();
    private AppListAdapter adapter;
    private Prefs prefs;

    private boolean showSystem = false;
    private boolean onlyNew = false;
    private boolean onlyBlocked = false;
    private boolean busy = false;
    private boolean pendingReload = false;      // 加载中又来了刷新请求 → 排队
    private boolean authGranted = false;        // ★ 缓存权限状态，避免每按键查系统
    private String query = "";
    private int pendingDialog = 0;
    private int newCount = 0;
    private long loadStartMs = 0;

    // ================================================================ 生命周期
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        applySystemBars();
        sInstance = this;
        prefs = new Prefs(this);
        showSystem = prefs.showSystem();
        onlyNew = prefs.onlyNew();

        listView = (ListView) findViewById(R.id.list);
        status = (TextView) findViewById(R.id.status);
        search = (EditText) findViewById(R.id.search);
        authBar = findViewById(R.id.auth_bar);
        batteryBar = findViewById(R.id.battery_bar);
        btnGrant = (Button) findViewById(R.id.btn_grant);
        btnBattery = (Button) findViewById(R.id.btn_battery);
        btnBatteryIgnore = (Button) findViewById(R.id.btn_battery_ignore);
        btnRefresh = (Button) findViewById(R.id.btn_refresh);
        btnAbout = (Button) findViewById(R.id.btn_about);
        btnClear = (Button) findViewById(R.id.btn_clear);
        btnNew = (Button) findViewById(R.id.btn_new);
        btnSystem = (Button) findViewById(R.id.btn_system);
        btnBlocked = (Button) findViewById(R.id.btn_blocked);
        btnBlockAll = (Button) findViewById(R.id.btn_block_all);
        btnAllowAll = (Button) findViewById(R.id.btn_allow_all);

        adapter = new AppListAdapter(this, shown);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(this);

        btnGrant.setOnClickListener(this);
        btnBattery.setOnClickListener(this);
        btnBatteryIgnore.setOnClickListener(this);
        btnRefresh.setOnClickListener(this);
        btnAbout.setOnClickListener(this);
        btnClear.setOnClickListener(this);
        btnNew.setOnClickListener(this);
        btnSystem.setOnClickListener(this);
        btnBlocked.setOnClickListener(this);
        btnBlockAll.setOnClickListener(this);
        btnAllowAll.setOnClickListener(this);
        search.addTextChangedListener(this);

        refreshAuthBar();       // ★ 先拿到权限状态，load() 才能给出正确的提示
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAuthBar();
        // 从设置页回来时，权限状态可能变了
        if (NotifAccess.isGranted(this) && all.isEmpty() && !busy) {
            load();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
        pool.shutdownNow();
    }

    /** 供广播接收器调用：有新装/卸载时刷新界面。 */
    public static void notifyDataChanged() {
        final MainActivity a = sInstance;
        if (a == null) {
            return;
        }
        a.ui.post(new ReloadTask(a));
    }

    /** 命名静态类，替代匿名 Runnable（保持代码对 d8 安全）。 */
    private static class ReloadTask implements Runnable {
        private final MainActivity a;

        ReloadTask(MainActivity a) {
            this.a = a;
        }

        @Override
        public void run() {
            // ★ 正在加载时不能直接丢弃请求，否则"新装"会一直不显示
            if (a.busy) {
                a.pendingReload = true;
            } else {
                a.load();
            }
        }
    }

    /** 把状态栏设成白色 + 深色图标，和界面融为一体。 */
    private void applySystemBars() {
        try {
            android.view.Window w = getWindow();
            w.setStatusBarColor(android.graphics.Color.WHITE);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.view.View dv = w.getDecorView();
                int flags = dv.getSystemUiVisibility();
                flags |= 0x00002000;            // LIGHT_STATUS_BAR
                dv.setSystemUiVisibility(flags);
            }
            w.setNavigationBarColor(android.graphics.Color.WHITE);
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ 点击
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position,
                            long id) {
        if (position >= 0 && position < shown.size()) {
            toggle(shown.get(position));
        }
    }

    @Override
    public void onClick(View v) {
        if (v == btnGrant) {
            NotifAccess.openSettings(this);
        } else if (v == btnBattery) {
            NotifAccess.requestIgnoreBatteryOptimizations(this);
        } else if (v == btnBatteryIgnore) {
            batteryBar.setVisibility(View.GONE);
            prefs.setBatteryHintDismissed(true);
        } else if (v == btnRefresh) {
            load();
        } else if (v == btnAbout) {
            showAbout();
        } else if (v == btnClear) {
            search.setText("");
        } else if (v == btnNew) {
            onlyNew = !onlyNew;
            prefs.setOnlyNew(onlyNew);
            applyFilter();
        } else if (v == btnSystem) {
            showSystem = !showSystem;
            prefs.setShowSystem(showSystem);
            applyFilter();
        } else if (v == btnBlocked) {
            onlyBlocked = !onlyBlocked;
            applyFilter();
        } else if (v == btnBlockAll) {
            confirmBulk(DLG_BLOCK_ALL);
        } else if (v == btnAllowAll) {
            confirmBulk(DLG_ALLOW_ALL);
        }
    }

    /** 关于 / 隐私政策（上架应用商店必需）。 */
    private void showAbout() {
        String text =
                "版本：1.0\n\n"
                + "【这个应用做什么】\n"
                + "读取并拦截你指定应用的通知。"
                + "所有操作都在本机完成。\n\n"
                + "【隐私政策】\n"
                + "1. 本应用不申请联网权限，不会上传任何数据。\n"
                + "2. 拦截通知时，通知内容仅在本机内存中实时比对，"
                + "处理完立即丢弃，不存储、不记录、不上传。\n"
                + "3. 不收集设备信息、不收集使用统计、"
                + "不集成任何第三方 SDK。\n"
                + "4. 拦截名单仅保存在本机，卸载应用即彻底删除。\n\n"
                + "【需要的权限及用途】\n"
                + "· 通知使用权：读取通知以便拦截，这是唯一的技术手段。\n"
                + "· 读取应用列表：用于展示和选择要拦截的应用。\n\n"
                + "【说明】\n"
                + "本应用通过「取消通知」实现拦截，"
                + "极短时间内可能有轻微延迟，属正常现象。\n"
                + "部分系统级通知（如前台服务）不会被拦截。";
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("关于 通知管家");
        b.setMessage(text);
        b.setPositiveButton("知道了", this);
        b.show();
    }

    private void confirmBulk(int which) {
        if (busy) {
            return;
        }
        pendingDialog = which;
        int n = shown.size();
        String msg;
        if (which == DLG_BLOCK_ALL) {
            msg = "将对当前列表中的 " + n + " 个应用拦截通知。\n\n"
                    + "⚠️ 注意：这会让你收不到这些应用的通知，"
                    + "包括来电、短信、闹钟、微信等。\n"
                    + "建议先用搜索或筛选缩小范围。\n\n确定继续吗？";
        } else {
            msg = "将恢复当前列表中 " + n + " 个应用的通知。\n\n确定继续吗？";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(which == DLG_BLOCK_ALL ? "全部拦截" : "全部恢复");
        b.setMessage(msg);
        b.setPositiveButton("确定", this);
        b.setNegativeButton("取消", this);
        b.show();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        dialog.dismiss();
        if (which != DialogInterface.BUTTON_POSITIVE || pendingDialog == 0) {
            return;
        }
        int mode = pendingDialog;
        pendingDialog = 0;
        bulk(mode == DLG_BLOCK_ALL);
    }

    // ================================================================ 输入
    @Override
    public void beforeTextChanged(CharSequence s, int a, int b, int c) {
    }

    @Override
    public void onTextChanged(CharSequence s, int a, int b, int c) {
        query = s == null ? "" : s.toString().trim();
        applyFilter();
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

    // ================================================================ 加载
    private void load() {
        if (busy) {
            pendingReload = true;         // 排队，别丢
            return;
        }
        busy = true;
        loadStartMs = System.currentTimeMillis();
        if (!authGranted) {
            setStatus("尚未开启「通知使用权」，点击上方按钮开启后才能拦截通知。");
        } else {
            setStatus("正在读取应用列表 …");
        }
        pool.execute(new LoadTask(this));
    }

    private static class LoadTask implements Runnable {
        private final MainActivity a;

        LoadTask(MainActivity a) {
            this.a = a;
        }

        @Override
        public void run() {
            PackageManager pm = a.getPackageManager();
            List<ApplicationInfo> infos = pm.getInstalledApplications(0);

            // ★ 收集当前所有包名
            Set<String> current = new HashSet<String>();
            for (int i = 0; i < infos.size(); i++) {
                current.add(infos.get(i).packageName);
            }

            // ★ 发现新装应用：广播记录的 ∪ （当前 - 已知）
            Set<String> fresh = a.prefs.newApps();
            if (!a.prefs.isFirstRun()) {
                for (String p : current) {
                    if (!a.prefs.known().contains(p)) {
                        fresh.add(p);
                    }
                }
            }
            fresh.remove(a.getPackageName());
            fresh.retainAll(current);          // 卸载掉的清掉
            a.prefs.setNewApps(fresh);
            a.prefs.setKnown(current);

            Set<String> blocked = a.prefs.blocked();

            List<AppEntry> list = new ArrayList<AppEntry>();
            for (int i = 0; i < infos.size(); i++) {
                ApplicationInfo ai = infos.get(i);
                if (ai.packageName.equals(a.getPackageName())) {
                    continue;
                }
                // 过滤掉没有任何界面的纯底层包，避免列表太乱
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                String label;
                try {
                    label = pm.getApplicationLabel(ai).toString();
                } catch (Throwable t) {
                    label = ai.packageName;
                }
                list.add(new AppEntry(
                        label,
                        ai.packageName,
                        ai.uid,
                        pm.getApplicationIcon(ai),
                        system,
                        fresh.contains(ai.packageName),
                        blocked.contains(ai.packageName)));
            }
            Collections.sort(list, new AppComparator());

            a.ui.post(new LoadedTask(a, list, fresh.size()));
        }
    }

    private static class LoadedTask implements Runnable {
        private final MainActivity a;
        private final List<AppEntry> list;
        private final int newCount;

        LoadedTask(MainActivity a, List<AppEntry> list, int newCount) {
            this.a = a;
            this.list = list;
            this.newCount = newCount;
        }

        @Override
        public void run() {
            a.all.clear();
            a.all.addAll(list);
            a.newCount = newCount;
            a.busy = false;
            android.util.Log.i("NotifButler", "列表已加载: "
                    + list.size() + " 个应用，耗时 "
                    + (System.currentTimeMillis() - a.loadStartMs) + " ms");
            a.applyFilter();
            // ★ 加载期间攒下的刷新请求，现在补做
            if (a.pendingReload) {
                a.pendingReload = false;
                a.load();
            }
        }
    }

    // ================================================================ 单个切换
    private void toggle(AppEntry e) {
        if (busy) {
            return;
        }
        boolean target = !e.blocked;
        prefs.setBlocked(e.pkg, target);
        e.blocked = target;
        adapter.notifyDataSetChanged();
        refreshStatus();
        Toast.makeText(this, e.label + (target ? " 已加入拦截" : " 已恢复通知"),
                Toast.LENGTH_SHORT).show();

        // 新装应用被处理过就不再标记
        if (e.isNew) {
            e.isNew = false;
            Set<String> one = new HashSet<String>();
            one.add(e.pkg);
            prefs.removeNewApps(one);
            if (newCount > 0) {
                newCount--;
            }
        }
    }

    // ================================================================ 批量
    private void bulk(final boolean block) {
        busy = true;
        final List<AppEntry> targets = new ArrayList<AppEntry>(shown);
        // ★ 操作前先拍个快照，万一误触可以一键撤销
        final Set<String> snapshot = prefs.blocked();

        Set<String> pkgs = new HashSet<String>();
        for (int i = 0; i < targets.size(); i++) {
            pkgs.add(targets.get(i).pkg);
        }
        prefs.setBlockedAll(pkgs, block);

        // ★ 只清掉【本次操作到的】新装标记，
        //   不能整个清空 —— 否则别的筛选条件下的新装标记也会丢
        int cleared = 0;
        Set<String> fresh = new HashSet<String>();
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).isNew) {
                fresh.add(targets.get(i).pkg);
                targets.get(i).isNew = false;
                cleared++;
            }
            targets.get(i).blocked = block;
        }
        if (cleared > 0) {
            prefs.removeNewApps(fresh);
            newCount = Math.max(0, newCount - cleared);
        }

        busy = false;
        applyFilter();

        // ★ 弹出可撤销的确认框（一键操作影响面大，必须给后悔的机会）
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("已完成");
        b.setMessage("已对 " + targets.size() + " 个应用"
                + (block ? "拦截" : "恢复") + "通知。\n\n点「撤销」可以恢复操作前的状态。");
        b.setPositiveButton("撤销", new UndoListener(this, snapshot, block));
        b.setNegativeButton("好的", this);
        b.show();
    }

    /** 命名类（避免匿名内部类）。 */
    private static class UndoListener
            implements DialogInterface.OnClickListener {
        private final MainActivity a;
        private final Set<String> snapshot;
        private final boolean wasBlock;

        UndoListener(MainActivity a, Set<String> snapshot, boolean wasBlock) {
            this.a = a;
            this.snapshot = snapshot;
            this.wasBlock = wasBlock;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();
            a.undoBulk(snapshot);
        }
    }

    /** 撤销上一次批量操作。 */
    private void undoBulk(Set<String> snapshot) {
        prefs.replaceBlocked(snapshot);
        // 重新按存储刷新每个条目的状态
        Set<String> now = prefs.blocked();
        for (int i = 0; i < all.size(); i++) {
            AppEntry e = all.get(i);
            e.blocked = now.contains(e.pkg);
        }
        applyFilter();
        Toast.makeText(this, "已撤销", Toast.LENGTH_SHORT).show();
    }

    // ================================================================ 工具
    private static class AppComparator implements Comparator<AppEntry> {
        @Override
        public int compare(AppEntry x, AppEntry y) {
            // 新装应用排最前
            if (x.isNew != y.isNew) {
                return x.isNew ? -1 : 1;
            }
            if (x.system != y.system) {
                return x.system ? 1 : -1;
            }
            return x.label.compareToIgnoreCase(y.label);
        }
    }

    private void refreshAuthBar() {
        authGranted = NotifAccess.isGranted(this);      // ★ 只在必要时查系统
        boolean connected = prefs.isListenerConnected();
        TextView title = (TextView) findViewById(R.id.auth_title);
        TextView desc = (TextView) findViewById(R.id.auth_desc);

        if (!authGranted) {
            title.setText("⚠️ 还差一步：开启「通知使用权」");
            desc.setText("开启后本应用才能读取并拦截通知。"
                    + "这是系统提供的标准权限，不会读取你的隐私内容。");
            btnGrant.setText("去开启");
            authBar.setVisibility(View.VISIBLE);
        } else if (!connected) {
            // ★ 权限开着但服务没连上 —— 这是最隐蔽的失效状态
            title.setText("⚠️ 服务未连接，拦截当前无效");
            desc.setText("系统设置里的权限是开着的，"
                    + "但监听服务没有真正连上（可能被系统回收了）。"
                    + "\n请在打开的页面里，把「通知管家」关掉再重新打开一次。");
            btnGrant.setText("去重新连接");
            authBar.setVisibility(View.VISIBLE);
        } else {
            authBar.setVisibility(View.GONE);
        }

        // 电池优化提示：只在没加白名单、且用户没点过"不再提示"时显示
        boolean batteryOk = NotifAccess.isIgnoringBatteryOptimizations(this);
        boolean dismissed = prefs.batteryHintDismissed();
        batteryBar.setVisibility((batteryOk || dismissed)
                ? View.GONE : View.VISIBLE);
    }

    private void applyFilter() {
        shown.clear();
        for (int i = 0; i < all.size(); i++) {
            AppEntry e = all.get(i);
            if (!showSystem && e.system) {
                continue;
            }
            if (onlyNew && !e.isNew) {
                continue;
            }
            if (onlyBlocked && !e.blocked) {
                continue;
            }
            if (!e.matches(query)) {
                continue;
            }
            shown.add(e);
        }
        adapter.notifyDataSetChanged();

        btnNew.setText(onlyNew ? "全部应用" : "新装应用");
        btnSystem.setText(showSystem ? "隐藏系统" : "显示系统");
        btnBlocked.setText(onlyBlocked ? "显示全部" : "只看已拦");
        refreshStatus();
    }

    private void refreshStatus() {
        int blocked = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).blocked) {
                blocked++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(all.size()).append(" 个应用，已拦截 ")
                .append(blocked).append(" 个");
        if (newCount > 0) {
            sb.append("　🆕 新装 ").append(newCount).append(" 个");
        }
        sb.append("\n当前显示 ").append(shown.size()).append(" 个");
        if (query.length() > 0) {
            sb.append("　搜索「").append(query).append("」");
        }
        if (!authGranted) {
            sb.append("\n⚠️ 未开启通知使用权，拦截不会生效");
        }
        setStatus(sb.toString());
    }

    private void setStatus(String s) {
        status.setText(s);
    }
}
