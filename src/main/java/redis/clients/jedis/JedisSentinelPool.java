package redis.clients.jedis;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.util.Pool;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

public class JedisSentinelPool extends Pool<Jedis> {

  protected GenericObjectPoolConfig poolConfig;

  protected int connectionTimeout = Protocol.DEFAULT_TIMEOUT;
  protected int soTimeout = Protocol.DEFAULT_TIMEOUT;

  protected String password;
  protected String sentinelPassword;

  protected int database = Protocol.DEFAULT_DATABASE;

  protected String clientName;

  protected Set<MasterListener> masterListeners = new HashSet<MasterListener>();

  protected Logger log = Logger.getLogger(getClass().getName());

  private volatile JedisFactory factory;
  private volatile HostAndPort currentHostMaster;

  protected boolean ssl;
  protected SSLSocketFactory sslSocketFactory;
  protected SSLParameters sslParameters;
  protected HostnameVerifier hostnameVerifier;

  private final Object initPoolLock = new Object();

  public JedisSentinelPool(String masterName, Set<String> sentinels,
      final GenericObjectPoolConfig poolConfig) {
    this(masterName, sentinels, poolConfig, Protocol.DEFAULT_TIMEOUT, null,
        Protocol.DEFAULT_DATABASE);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels) {
    this(masterName, sentinels, new GenericObjectPoolConfig(), Protocol.DEFAULT_TIMEOUT, null,
        Protocol.DEFAULT_DATABASE);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels, String password) {
    this(masterName, sentinels, new GenericObjectPoolConfig(), Protocol.DEFAULT_TIMEOUT, password);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels, String password, final boolean ssl) {
    this(masterName, sentinels, new GenericObjectPoolConfig(), Protocol.DEFAULT_TIMEOUT, Protocol.DEFAULT_TIMEOUT, password,
            Protocol.DEFAULT_DATABASE, null, ssl, null, null, null, null);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels, String password,
                           final boolean ssl, final SSLSocketFactory sslSocketFactory,
                           final SSLParameters sslParameters, final HostnameVerifier hostnameVerifier) {
    this(masterName, sentinels, new GenericObjectPoolConfig(), Protocol.DEFAULT_TIMEOUT, Protocol.DEFAULT_TIMEOUT, password,
            Protocol.DEFAULT_DATABASE, null, ssl, sslSocketFactory, sslParameters, hostnameVerifier, null);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
      final GenericObjectPoolConfig poolConfig, int timeout, final String password) {
    this(masterName, sentinels, poolConfig, timeout, password, Protocol.DEFAULT_DATABASE);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
      final GenericObjectPoolConfig poolConfig, final int timeout) {
    this(masterName, sentinels, poolConfig, timeout, null, Protocol.DEFAULT_DATABASE);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
      final GenericObjectPoolConfig poolConfig, final String password) {
    this(masterName, sentinels, poolConfig, Protocol.DEFAULT_TIMEOUT, password);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
      final GenericObjectPoolConfig poolConfig, int timeout, final String password,
      final int database) {
    this(masterName, sentinels, poolConfig, timeout, timeout, password, database);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
      final GenericObjectPoolConfig poolConfig, int timeout, final String password,
      final int database, final String clientName) {
    this(masterName, sentinels, poolConfig, timeout, timeout, password, database, clientName);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
      final GenericObjectPoolConfig poolConfig, final int timeout, final int soTimeout,
      final String password, final int database) {
    this(masterName, sentinels, poolConfig, timeout, soTimeout, password, database, null);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
                           final GenericObjectPoolConfig poolConfig, final int connectionTimeout, final int soTimeout,
                           final String password, final int database, final String clientName) {
      this(masterName, sentinels, poolConfig, connectionTimeout, soTimeout, password, database, clientName, false);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
                           final GenericObjectPoolConfig poolConfig, final int connectionTimeout, final int soTimeout,
                           final String password, final int database, final String clientName, final boolean ssl) {
    this(masterName, sentinels, poolConfig, connectionTimeout, soTimeout, password, database, clientName, ssl, null, null, null, null);
  }

  public JedisSentinelPool(String masterName, Set<String> sentinels,
                           final GenericObjectPoolConfig poolConfig, final int connectionTimeout, final int soTimeout,
                           final String password, final int database, final String clientName,
                           final boolean ssl, final SSLSocketFactory sslSocketFactory,
                           final SSLParameters sslParameters, final HostnameVerifier hostnameVerifier,
                           String sentinelPassword) {
    this.poolConfig = poolConfig;
    this.connectionTimeout = connectionTimeout;
    this.soTimeout = soTimeout;
    this.password = password;
    this.database = database;
    this.clientName = clientName;
    this.ssl = ssl;
    this.sslSocketFactory = sslSocketFactory;
    this.sslParameters = sslParameters;
    this.hostnameVerifier = hostnameVerifier;
    this.sentinelPassword = sentinelPassword;

    HostAndPort master = initSentinels(sentinels, masterName);
    initPool(master);
  }

  @Override
  public void destroy() {
    for (MasterListener m : masterListeners) {
      m.shutdown();
    }

    super.destroy();
  }

  public HostAndPort getCurrentHostMaster() {
    return currentHostMaster;
  }

  private void initPool(HostAndPort master) {
    synchronized(initPoolLock){
      if (!master.equals(currentHostMaster)) {
        currentHostMaster = master;
        if (factory == null) {
          factory = new JedisFactory(master.getHost(), master.getPort(), connectionTimeout,
              soTimeout, password, database, clientName, ssl, sslSocketFactory, sslParameters, hostnameVerifier);
          initPool(poolConfig, factory);
        } else {
          factory.setHostAndPort(currentHostMaster);
          // although we clear the pool, we still have to check the
          // returned object
          // in getResource, this call only clears idle instances, not
          // borrowed instances
          internalPool.clear();
        }

        log.info("Created JedisPool to master at " + master);
      }
    }
  }

  private HostAndPort initSentinels(Set<String> sentinels, final String masterName) {

    HostAndPort master = null;
    boolean sentinelAvailable = false;

    log.info("Trying to find master from available Sentinels...");

    for (String sentinel : sentinels) {
      final HostAndPort hap = HostAndPort.parseString(sentinel);

      log.fine("Connecting to Sentinel " + hap);

      Jedis jedis = null;
      try {
        jedis = new Jedis(hap.getHost(), hap.getPort(), this.ssl, this.sslSocketFactory, this.sslParameters, this.hostnameVerifier);
        if (this.sentinelPassword != null) {
          jedis.auth(this.sentinelPassword);
        }
        List<String> masterAddr = jedis.sentinelGetMasterAddrByName(masterName);

        // connected to sentinel...
        sentinelAvailable = true;

        if (masterAddr == null || masterAddr.size() != 2) {
          log.warning("Can not get master addr, master name: " + masterName + ". Sentinel: " + hap
              + ".");
          continue;
        }

        master = toHostAndPort(masterAddr);
        log.fine("Found Redis master at " + master);
        break;
      } catch (JedisException e) {
        // resolves #1036, it should handle JedisException there's another chance
        // of raising JedisDataException
        log.warning("Cannot get master address from sentinel running @ " + hap + ". Reason: " + e
            + ". Trying next one.");
      } finally {
        if (jedis != null) {
          jedis.close();
        }
      }
    }

    if (master == null) {
      if (sentinelAvailable) {
        // can connect to sentinel, but master name seems to not
        // monitored
        throw new JedisException("Can connect to sentinel, but " + masterName
            + " seems to be not monitored...");
      } else {
        throw new JedisConnectionException("All sentinels down, cannot determine where is "
            + masterName + " master is running...");
      }
    }

    log.info("Redis master running at " + master + ", starting Sentinel listeners...");

    for (String sentinel : sentinels) {
      final HostAndPort hap = HostAndPort.parseString(sentinel);
      MasterListener masterListener = new MasterListener(masterName, hap.getHost(), hap.getPort(), ssl, sslSocketFactory, sslParameters, hostnameVerifier, sentinelPassword);
      // whether MasterListener threads are alive or not, process can be stopped
      masterListener.setDaemon(true);
      masterListeners.add(masterListener);
      masterListener.start();
    }

    return master;
  }

  protected HostAndPort toHostAndPort(List<String> getMasterAddrByNameResult) {
    String host = getMasterAddrByNameResult.get(0);
    int port = Integer.parseInt(getMasterAddrByNameResult.get(1));

    return new HostAndPort(host, port);
  }

  @Override
  public Jedis getResource() {
    while (true) {
      Jedis jedis = super.getResource();
      jedis.setDataSource(this);

      // get a reference because it can change concurrently
      final HostAndPort master = currentHostMaster;
      final HostAndPort connection = new HostAndPort(jedis.getClient().getHost(), jedis.getClient()
          .getPort());

      if (master.equals(connection)) {
        // connected to the correct master
        return jedis;
      } else {
        returnBrokenResource(jedis);
      }
    }
  }

  /**
   * @deprecated starting from Jedis 3.0 this method will not be exposed. Resource cleanup should be
   *             done using @see {@link redis.clients.jedis.Jedis#close()}
   */
  @Override
  @Deprecated
  public void returnBrokenResource(final Jedis resource) {
    if (resource != null) {
      returnBrokenResourceObject(resource);
    }
  }

  /**
   * @deprecated starting from Jedis 3.0 this method will not be exposed. Resource cleanup should be
   *             done using @see {@link redis.clients.jedis.Jedis#close()}
   */
  @Override
  @Deprecated
  public void returnResource(final Jedis resource) {
    if (resource != null) {
      resource.resetState();
      returnResourceObject(resource);
    }
  }

  protected class MasterListener extends Thread {

    protected String masterName;
    protected String host;
    protected int port;
    protected boolean ssl;
    protected SSLSocketFactory sslSocketFactory;
    protected SSLParameters sslParameters;
    protected HostnameVerifier hostnameVerifier;
    protected long subscribeRetryWaitTimeMillis = 5000;
    protected volatile Jedis j;
    protected AtomicBoolean running = new AtomicBoolean(false);
    protected String sentinelPassword;

    protected MasterListener() {
    }

    public MasterListener(String masterName, String host, int port, boolean ssl,
                          SSLSocketFactory sslSocketFactory, SSLParameters sslParameters,
                          HostnameVerifier hostnameVerifier, String sentinelPassword) {
      super(String.format("MasterListener-%s-[%s:%d]", masterName, host, port));
      this.masterName = masterName;
      this.host = host;
      this.port = port;
      this.ssl = ssl;
      this.sslSocketFactory = sslSocketFactory;
      this.sslParameters = sslParameters;
      this.hostnameVerifier = hostnameVerifier;
      this.sentinelPassword = sentinelPassword;
    }

    public MasterListener(String masterName, String host, int port,
                          boolean ssl, SSLSocketFactory sslSocketFactory, SSLParameters sslParameters,
                          HostnameVerifier hostnameVerifier, long subscribeRetryWaitTimeMillis) {
      this(masterName, host, port, ssl, sslSocketFactory, sslParameters, hostnameVerifier, null);
      this.subscribeRetryWaitTimeMillis = subscribeRetryWaitTimeMillis;
    }

    public MasterListener(String masterName, String host, int port,
        long subscribeRetryWaitTimeMillis) {
      this(masterName, host, port, false, null, null, null, null);
      this.subscribeRetryWaitTimeMillis = subscribeRetryWaitTimeMillis;
    }

    @Override
    public void run() {

      running.set(true);

      while (running.get()) {

        j = new Jedis(host, port, ssl, sslSocketFactory, sslParameters, hostnameVerifier);

        try {
          // double check that it is not being shutdown
          if (!running.get()) {
            break;
          }
          
          /*
           * Added code for active refresh
           */
          if (this.sentinelPassword != null) {
            j.auth(this.sentinelPassword);
          }
          List<String> masterAddr = j.sentinelGetMasterAddrByName(masterName);
          if (masterAddr == null || masterAddr.size() != 2) {

            log.warning("Can not get master addr, master name: "+ masterName+". Sentinel: "+host+"："+port+".");
          }else{
              initPool(toHostAndPort(masterAddr)); 
          }

          j.subscribe(new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
              log.fine("Sentinel " + host + ":" + port + " published: " + message + ".");

              String[] switchMasterMsg = message.split(" ");

              if (switchMasterMsg.length > 3) {

                if (masterName.equals(switchMasterMsg[0])) {
                  initPool(toHostAndPort(Arrays.asList(switchMasterMsg[3], switchMasterMsg[4])));
                } else {
                  log.fine("Ignoring message on +switch-master for master name "
                      + switchMasterMsg[0] + ", our master name is " + masterName);
                }

              } else {
                log.severe("Invalid message received on Sentinel " + host + ":" + port
                    + " on channel +switch-master: " + message);
              }
            }
          }, "+switch-master");

        } catch (JedisException e) {

          if (running.get()) {
            log.log(Level.SEVERE, "Lost connection to Sentinel at " + host + ":" + port
                + ". Sleeping 5000ms and retrying.", e);
            try {
              Thread.sleep(subscribeRetryWaitTimeMillis);
            } catch (InterruptedException e1) {
              log.log(Level.SEVERE, "Sleep interrupted: ", e1);
            }
          } else {
            log.fine("Unsubscribing from Sentinel at " + host + ":" + port);
          }
        } finally {
          j.close();
        }
      }
    }

    public void shutdown() {
      try {
        log.fine("Shutting down listener on " + host + ":" + port);
        running.set(false);
        // This isn't good, the Jedis object is not thread safe
        if (j != null) {
          j.disconnect();
        }
      } catch (Exception e) {
        log.log(Level.SEVERE, "Caught exception while shutting down: ", e);
      }
    }
  }
}