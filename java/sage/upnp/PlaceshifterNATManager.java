/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.upnp;

import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.controlpoint.ActionCallback;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.message.header.UDADeviceTypeHeader;
import org.jupnp.model.meta.Device;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.Service;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDAServiceType;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;
import sage.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * JUPnP-based UPnP NAT manager for Placeshifter port forwarding.
 * Migrated from SBBI (sbbi-upnplib) to JUPnP. Behaviour and all Sage.properties keys are
 * identical to the original implementation.
 */
public class PlaceshifterNATManager implements Runnable, AbstractedService
{
  private volatile UpnpService upnpService;
  private final List<RemoteDevice> allIgdDevices = new ArrayList<>();

  private final DefaultRegistryListener registryListener = new DefaultRegistryListener()
  {
    @Override public void remoteDeviceAdded(Registry registry, RemoteDevice device)
    {
      if (isIGD(device)) synchronized (allIgdDevices) { allIgdDevices.add(device); }
      synchronized (waitLock) { waitLock.notifyAll(); }
    }
    @Override public void remoteDeviceRemoved(Registry registry, RemoteDevice device)
    {
      synchronized (allIgdDevices) { allIgdDevices.remove(device); }
      if (device.equals(myRouter)) { myRouter = null; myWanService = null;
        if (Sage.DBG) System.out.println("UPnP: configured router left the network"); }
    }
  };

  private volatile RemoteDevice myRouter;
  @SuppressWarnings("rawtypes") private volatile Service myWanService;
  private volatile String myExternalIP;
  private Thread myThread;
  private volatile boolean alive;
  private final Object waitLock = new Object();

  @Override public void start()
  {
    upnpService = new UpnpServiceImpl();
    upnpService.getRegistry().addListener(registryListener);
    alive = true;
    myThread = new Thread(this, "PSNATMGR");
    myThread.setDaemon(true);
    myThread.setPriority(Thread.MIN_PRIORITY);
    myThread.start();
  }

  @Override public void kill()
  {
    alive = false;
    synchronized (waitLock) { waitLock.notifyAll(); }
    try { myThread.join(2000); } catch (Exception e) {}
    if (myRouter != null) removeMappings();
    UpnpService svc = upnpService;
    if (svc != null) { upnpService = null; svc.shutdown(); }
  }

  @Override public void run()
  {
    if (Sage.DBG) System.out.println("Starting UPnP NAT Manager (JUPnP)...");
    if (usesUPnP()) { findMyRouter(); synchronizeMappings(); }
    while (alive)
    {
      if (Sage.getBoolean("placeshifter_port_forward_upnp_active", false) || !usesUPnP())
        { synchronized (waitLock) { try { waitLock.wait(15*60000); } catch (InterruptedException e) {} } continue; }
      findMyRouter();
      synchronizeMappings();
      synchronized (waitLock) { try { waitLock.wait(15*60000); } catch (InterruptedException e) {} }
    }
  }

  private boolean usesUPnP()
  { return "xUPnP".equals(Sage.get("placeshifter_port_forward_method", null)); }

  private void discoverRouters()
  {
    UpnpService svc = upnpService;
    if (svc == null) return;
    synchronized (allIgdDevices)
    { allIgdDevices.clear(); for (RemoteDevice d : svc.getRegistry().getRemoteDevices()) if (isIGD(d)) allIgdDevices.add(d); }
    boolean isEmpty; synchronized (allIgdDevices) { isEmpty = allIgdDevices.isEmpty(); }
    if (isEmpty)
    {
      if (Sage.DBG) System.out.println("UPnP: M-SEARCH for InternetGatewayDevice");
      svc.getControlPoint().search(new UDADeviceTypeHeader(new UDADeviceType("InternetGatewayDevice")));
      try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }

  private void findMyRouter()
  {
    if (myRouter != null)
    {
      String old = myExternalIP; myExternalIP = getExternalIP();
      if ((old == null && myExternalIP != null) || (old != null && !old.equals(myExternalIP))) SageTV.forceLocatorUpdate();
      return;
    }
    discoverRouters();
    List<RemoteDevice> snap; synchronized (allIgdDevices) { snap = new ArrayList<>(allIgdDevices); }
    if (snap.isEmpty()) return;
    String wantedUDN = Sage.get("placeshifter_port_forward_upnp_udn", null);
    for (RemoteDevice dev : snap)
    {
      if (dev.getIdentity().getUdn().getIdentifierString().equals(wantedUDN))
        { myRouter = dev; myWanService = findWanService(dev); break; }
    }
    if (myRouter == null)
    {
      if (Sage.getBoolean("placeshifter_port_forward_upnp_active", false)) return;
      for (RemoteDevice dev : snap)
      {
        @SuppressWarnings("rawtypes") Service ws = findWanService(dev);
        if (ws == null) continue;
        myRouter = dev; myWanService = ws;
        String ip = getExternalIP();
        if (ip != null && !ip.equals("0.0.0.0"))
        {
          String udn = dev.getIdentity().getUdn().getIdentifierString();
          System.out.println("Previously configured UPnP router not found. Using: " + udn);
          Sage.put("placeshifter_port_forward_upnp_udn", udn);
          myExternalIP = ip; SageTV.forceLocatorUpdate(); return;
        }
        myRouter = null; myWanService = null;
      }
      System.out.println("Cannot find a valid UPnP router on the network!");
      synchronized (allIgdDevices) { allIgdDevices.clear(); }
    }
    else myExternalIP = getExternalIP();
  }

  private void synchronizeMappings()
  {
    if (myRouter == null || myWanService == null) return;
    String localIP = null;
    try { localIP = sage.Sage.WINDOWS_OS || sage.Sage.MAC_OS_X ? java.net.InetAddress.getLocalHost().getHostAddress() : LinuxUtils.getIPAddress(); }
    catch (java.io.IOException e) { if (Sage.DBG) System.out.println("UPnP: cannot determine local IP"); return; }
    int localPort = Sage.getInt("extender_and_placeshifter_server_port", 31099);
    int extPort   = Sage.getInt("placeshifter_port_forward_extern_port", localPort);
    if (extPort > 0) setupMapping(localIP, localPort, extPort, "SageTV", "TCP");
    for (String svc : Sage.childrenNames("upnp_port_forward_additional_mappings"))
      for (String type : Sage.childrenNames("upnp_port_forward_additional_mappings/" + svc))
      {
        if (!type.equals("TCP") && !type.equals("UDP")) continue;
        for (String ep : Sage.keys("upnp_port_forward_additional_mappings/" + svc + "/" + type))
        {
          int ep2; try { ep2 = Integer.parseInt(ep); } catch (NumberFormatException e) { continue; }
          int ip2 = Sage.getInt("upnp_port_forward_additional_mappings/" + svc + "/" + type + "/" + ep2, 0);
          if (ip2 > 0 && ep2 > 0) setupMapping(localIP, ip2, ep2, svc, type);
        }
      }
  }

  @SuppressWarnings("rawtypes")
  private boolean setupMapping(String localIP, int localPort, int extPort, String svcName, String proto)
  {
    Service ws = myWanService;
    if (ws == null) return false;
    String[] ex = getSpecificPortMapping(ws, extPort, proto);
    if (ex != null)
    {
      if (localIP.equals(ex[0]) && Integer.toString(localPort).equals(ex[1])) return true;
      if (Sage.getBoolean("placeshifter_port_forward_upnp_active", false)) return true;
      deletePortMapping(ws, extPort, proto);
    }
    if (Sage.getBoolean("placeshifter_port_forward_upnp_active", false)) return true;
    boolean ok = addPortMapping(ws, localIP, localPort, extPort, svcName, proto);
    if (!ok) { myRouter = null; myWanService = null; synchronized (allIgdDevices) { allIgdDevices.clear(); } }
    else if (Sage.DBG) System.out.println("UPnP: mapped ext=" + extPort + " -> " + localIP + ":" + localPort);
    return ok;
  }

  @SuppressWarnings("rawtypes")
  private void removeMappings()
  {
    Service ws = myWanService;
    if (ws == null) return;
    int ep = Sage.getInt("placeshifter_port_forward_extern_port", Sage.getInt("extender_and_placeshifter_server_port", 31099));
    if (ep > 0) deletePortMapping(ws, ep, "TCP");
    for (String svc : Sage.childrenNames("upnp_port_forward_additional_mappings"))
      for (String type : Sage.childrenNames("upnp_port_forward_additional_mappings/" + svc))
      {
        if (!type.equals("TCP") && !type.equals("UDP")) continue;
        for (String e : Sage.keys("upnp_port_forward_additional_mappings/" + svc + "/" + type))
          try { deletePortMapping(ws, Integer.parseInt(e), type); } catch (NumberFormatException ex) {}
      }
  }

  // WANIPConnection actions via JUPnP core ActionCallback (no support-module dependency).

  @SuppressWarnings({"rawtypes","unchecked"})
  private String getExternalIP()
  {
    Service ws = myWanService; UpnpService usvc = upnpService;
    if (ws == null || usvc == null) return null;
    org.jupnp.model.meta.Action act = ws.getAction("GetExternalIPAddress");
    if (act == null) return null;
    CompletableFuture<String> f = new CompletableFuture<>();
    usvc.getControlPoint().execute(new ActionCallback(new ActionInvocation(act))
    {
      @Override public void success(ActionInvocation inv)
      { try { Object v = inv.getOutput("NewExternalIPAddress").getValue(); f.complete(v!=null?v.toString():null); } catch (Exception e) { f.complete(null); } }
      @Override public void failure(ActionInvocation inv, UpnpResponse r, String msg)
      { if (Sage.DBG) System.out.println("UPnP GetExternalIPAddress: "+msg); f.complete(null); }
    });
    try { return f.get(5,TimeUnit.SECONDS); } catch (Exception e) { return null; }
  }

  @SuppressWarnings({"rawtypes","unchecked"})
  private String[] getSpecificPortMapping(Service ws, int extPort, String proto)
  {
    UpnpService usvc = upnpService; if (usvc == null) return null;
    org.jupnp.model.meta.Action act = ws.getAction("GetSpecificPortMappingEntry");
    if (act == null) return null;
    ActionInvocation inv = new ActionInvocation(act);
    try { inv.setInput("NewRemoteHost",""); inv.setInput("NewExternalPort",extPort); inv.setInput("NewProtocol",proto); }
    catch (Exception e) { return null; }
    CompletableFuture<String[]> f = new CompletableFuture<>();
    usvc.getControlPoint().execute(new ActionCallback(inv)
    {
      @Override public void success(ActionInvocation i)
      { try { f.complete(new String[]{i.getOutput("NewInternalClient").getValue().toString(),i.getOutput("NewInternalPort").getValue().toString()}); } catch(Exception e){f.complete(null);} }
      @Override public void failure(ActionInvocation i,UpnpResponse r,String msg){ f.complete(null); }
    });
    try { return f.get(5,TimeUnit.SECONDS); } catch (Exception e) { return null; }
  }

  @SuppressWarnings({"rawtypes","unchecked"})
  private boolean addPortMapping(Service ws, String localIP, int localPort, int extPort, String desc, String proto)
  {
    UpnpService usvc = upnpService; if (usvc == null) return false;
    org.jupnp.model.meta.Action act = ws.getAction("AddPortMapping");
    if (act == null) return false;
    ActionInvocation inv = new ActionInvocation(act);
    try
    {
      inv.setInput("NewRemoteHost",""); inv.setInput("NewExternalPort",extPort); inv.setInput("NewProtocol",proto);
      inv.setInput("NewInternalPort",localPort); inv.setInput("NewInternalClient",localIP);
      inv.setInput("NewEnabled","1"); inv.setInput("NewPortMappingDescription",desc); inv.setInput("NewLeaseDuration","0");
    }
    catch (Exception e) { System.out.println("UPnP AddPortMapping build error: "+e); return false; }
    CompletableFuture<Boolean> f = new CompletableFuture<>();
    usvc.getControlPoint().execute(new ActionCallback(inv)
    {
      @Override public void success(ActionInvocation i){ f.complete(true); }
      @Override public void failure(ActionInvocation i,UpnpResponse r,String msg){ System.out.println("UPnP AddPortMapping: "+msg); f.complete(false); }
    });
    try { return Boolean.TRUE.equals(f.get(5,TimeUnit.SECONDS)); } catch (Exception e) { return false; }
  }

  @SuppressWarnings({"rawtypes","unchecked"})
  private void deletePortMapping(Service ws, int extPort, String proto)
  {
    UpnpService usvc = upnpService; if (usvc == null) return;
    org.jupnp.model.meta.Action act = ws.getAction("DeletePortMapping");
    if (act == null) return;
    ActionInvocation inv = new ActionInvocation(act);
    try { inv.setInput("NewRemoteHost",""); inv.setInput("NewExternalPort",extPort); inv.setInput("NewProtocol",proto); }
    catch (Exception e) { return; }
    CompletableFuture<Void> f = new CompletableFuture<>();
    usvc.getControlPoint().execute(new ActionCallback(inv)
    {
      @Override public void success(ActionInvocation i){ f.complete(null); }
      @Override public void failure(ActionInvocation i,UpnpResponse r,String msg){ if(Sage.DBG)System.out.println("UPnP DeletePortMapping: "+msg); f.complete(null); }
    });
    try { f.get(5,TimeUnit.SECONDS); } catch (Exception e) {}
  }

  private static boolean isIGD(Device<?,?,?> d)
  { return "InternetGatewayDevice".equals(d.getType().getType()); }

  @SuppressWarnings("rawtypes")
  private Service findWanService(Device<?,?,?> d)
  {
    for (String t : new String[]{"WANIPConnection","WANPPPConnection"})
    { Service s = d.findService(new UDAServiceType(t,1)); if (s!=null) return s; }
    Device<?,?,?>[] emb = d.getEmbeddedDevices();
    if (emb != null) for (Device<?,?,?> e : emb) { Service s = findWanService(e); if (s!=null) return s; }
    return null;
  }
}
