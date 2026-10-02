package com.local.huaweicast;

import android.content.Context;
import android.net.nsd.*;
import android.os.*;
import java.util.*;

/** Uses Android's DNS-SD stack, keeping resolve requests serialized. */
public final class ServiceDiscovery {
    public interface Listener { void found(NsdServiceInfo info); void diagnostic(String message); }
    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> searches = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();
    private final Listener listener;
    private boolean resolving, stopped;
    public ServiceDiscovery(Context context, Listener listener) { manager = context.getSystemService(NsdManager.class); this.listener = listener; }
    public void start() {
        for (String type : new String[]{"_leboremote._tcp.", "_airplay._tcp.", "_raop._tcp."}) {
            NsdManager.DiscoveryListener search = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String serviceType) {}
                @Override public void onServiceFound(NsdServiceInfo service) { main.post(() -> { if (!stopped && seen.add(service.getServiceName() + service.getServiceType())) { pending.add(service); resolveNext(); } }); }
                @Override public void onServiceLost(NsdServiceInfo service) {}
                @Override public void onDiscoveryStopped(String serviceType) {}
                @Override public void onStartDiscoveryFailed(String serviceType, int error) { listener.diagnostic("mDNS " + serviceType + " 搜索失败: " + error); }
                @Override public void onStopDiscoveryFailed(String serviceType, int error) {}
            };
            searches.add(search);
            try { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, search); }
            catch (Exception error) { listener.diagnostic("mDNS: " + error.getMessage()); }
        }
    }
    private void resolveNext() {
        if (resolving || stopped || pending.isEmpty()) return;
        resolving = true;
        try { manager.resolveService(pending.remove(), new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int error) { main.post(() -> { resolving = false; listener.diagnostic("mDNS 解析失败: " + error); resolveNext(); }); }
            @Override public void onServiceResolved(NsdServiceInfo info) { main.post(() -> { resolving = false; if (!stopped) listener.found(info); resolveNext(); }); }
        }); } catch (Exception error) { resolving = false; listener.diagnostic(error.getMessage()); resolveNext(); }
    }
    public void stop() { stopped = true; pending.clear(); for (NsdManager.DiscoveryListener search : searches) { try { manager.stopServiceDiscovery(search); } catch (Exception ignored) {} } searches.clear(); }
}
