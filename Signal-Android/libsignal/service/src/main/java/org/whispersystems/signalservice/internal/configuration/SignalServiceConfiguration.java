package org.whispersystems.signalservice.internal.configuration;

import org.whispersystems.libsignal.util.guava.Optional;

import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.Interceptor;

public final class SignalServiceConfiguration {

  private final SignalServiceUrl[]           signalServiceUrls;
  private final Map<Integer, SignalCdnUrl[]> signalCdnUrlMap;
  private final SignalContactDiscoveryUrl[]  signalContactDiscoveryUrls;
  private final SignalCdshUrl[]              signalCdshUrls;
  private final SignalKeyBackupServiceUrl[]  signalKeyBackupServiceUrls;
  private final SignalStorageUrl[]           signalStorageUrls;
  private final List<Interceptor>            networkInterceptors;
  private final Optional<Dns>                dns;
  private final Optional<SignalProxy>        proxy;
  private final byte[]                       zkGroupServerPublicParams;

  public SignalServiceConfiguration(SignalServiceUrl[] signalServiceUrls,
                                    Map<Integer, SignalCdnUrl[]> signalCdnUrlMap,
                                    SignalContactDiscoveryUrl[] signalContactDiscoveryUrls,
                                    SignalKeyBackupServiceUrl[] signalKeyBackupServiceUrls,
                                    SignalStorageUrl[] signalStorageUrls,
                                    SignalCdshUrl[] signalCdshUrls,
                                    List<Interceptor> networkInterceptors,
                                    Optional<Dns> dns,
                                    Optional<SignalProxy> proxy,
                                    byte[] zkGroupServerPublicParams)
  {
    this.signalServiceUrls          = signalServiceUrls;
    this.signalCdnUrlMap            = signalCdnUrlMap;
    this.signalContactDiscoveryUrls = signalContactDiscoveryUrls;
    this.signalCdshUrls             = signalCdshUrls;
    this.signalKeyBackupServiceUrls = signalKeyBackupServiceUrls;
    this.signalStorageUrls          = signalStorageUrls;
    this.networkInterceptors        = networkInterceptors;
    this.dns                        = dns;
    this.proxy                      = proxy;
    this.zkGroupServerPublicParams  = zkGroupServerPublicParams;
  }

  public SignalServiceUrl[] getSignalServiceUrls() {
    return signalServiceUrls;
  }

  public Map<Integer, SignalCdnUrl[]> getSignalCdnUrlMap() {
    return signalCdnUrlMap;
  }

  public SignalContactDiscoveryUrl[] getSignalContactDiscoveryUrls() {
    return signalContactDiscoveryUrls;
  }

  public SignalCdshUrl[] getSignalCdshUrls() {
    return signalCdshUrls;
  }

  public SignalKeyBackupServiceUrl[] getSignalKeyBackupServiceUrls() {
    return signalKeyBackupServiceUrls;
  }

  public SignalStorageUrl[] getSignalStorageUrls() {
    return signalStorageUrls;
  }

  public List<Interceptor> getNetworkInterceptors() {
    return networkInterceptors;
  }

  public Optional<Dns> getDns() {
    return dns;
  }

  public byte[] getZkGroupServerPublicParams() {
    return zkGroupServerPublicParams;
  }

  public Optional<SignalProxy> getSignalProxy() {
    return proxy;
  }
}
