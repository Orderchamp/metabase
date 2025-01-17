(ns metabase.util.ssh
  (:require [clojure.tools.logging :as log]
            [metabase.driver :as driver]
            [metabase.public-settings :as public-settings]
            [metabase.util :as u]
            [metabase.util.i18n :as i18n :refer [deferred-tru]])
  (:import java.io.ByteArrayInputStream
           java.util.concurrent.TimeUnit
           org.apache.sshd.client.future.ConnectFuture
           org.apache.sshd.client.session.ClientSession
           org.apache.sshd.client.session.forward.PortForwardingTracker
           org.apache.sshd.client.SshClient
           [org.apache.sshd.common.config.keys FilePasswordProvider FilePasswordProvider$ResourceDecodeResult]
           [org.apache.sshd.common.session
            SessionHeartbeatController
            SessionHeartbeatController$HeartbeatType
            SessionHolder]
           org.apache.sshd.common.util.GenericUtils
           org.apache.sshd.common.util.io.resource.AbstractIoResource
           org.apache.sshd.common.util.net.SshdSocketAddress
           org.apache.sshd.common.util.security.SecurityUtils
           org.apache.sshd.server.forward.AcceptAllForwardingFilter))

(def ^:private ^Long default-ssh-timeout 30000)

(def ^:private ^SshClient client
  (doto (SshClient/setUpDefaultClient)
    (.start)
    (.setForwardingFilter AcceptAllForwardingFilter/INSTANCE)))

(defn- maybe-add-tunnel-password!
  [^ClientSession session ^String tunnel-pass]
  (when tunnel-pass
    (.addPasswordIdentity session tunnel-pass)))

(defn- maybe-add-tunnel-private-key!
  [^ClientSession session ^String tunnel-private-key tunnel-private-key-passphrase]
  (when tunnel-private-key
    (let [resource-key      (proxy [AbstractIoResource] [(class "key") "key"])
          password-provider (proxy [FilePasswordProvider] []
                              (getPassword [_ _ _]
                                tunnel-private-key-passphrase)
                              (handleDecodeAttemptResult [_ _ _ _ _]
                                FilePasswordProvider$ResourceDecodeResult/TERMINATE))
          ids               (with-open [is (ByteArrayInputStream. (.getBytes tunnel-private-key "UTF-8"))]
                              (SecurityUtils/loadKeyPairIdentities session resource-key is password-provider))
          keypair           (GenericUtils/head ids)]
      (.addPublicKeyIdentity session keypair))))

(defn start-ssh-tunnel!
  "Opens a new ssh tunnel and returns the connection along with the dynamically assigned tunnel entrance port. It's the
  callers responsibility to call `close-tunnel` on the returned connection object."
  [{:keys [^String tunnel-host ^Integer tunnel-port ^String tunnel-user tunnel-pass tunnel-private-key
           tunnel-private-key-passphrase host port]}]
  (let [^ConnectFuture conn-future (.connect client tunnel-user tunnel-host tunnel-port)
        ^SessionHolder conn-status (.verify conn-future default-ssh-timeout)
        hb-sec                     (public-settings/ssh-heartbeat-interval-sec)
        session                    (doto ^ClientSession (.getSession conn-status)
                                     (maybe-add-tunnel-password! tunnel-pass)
                                     (maybe-add-tunnel-private-key! tunnel-private-key tunnel-private-key-passphrase)
                                     (.setSessionHeartbeat SessionHeartbeatController$HeartbeatType/IGNORE
                                                           TimeUnit/SECONDS
                                                           hb-sec)
                                     (.. auth (verify default-ssh-timeout)))
        tracker                    (.createLocalPortForwardingTracker session
                                                                      (SshdSocketAddress. "" 0)
                                                                      (SshdSocketAddress. host port))
        input-port                 (.. tracker getBoundAddress getPort)]
    (log/trace (u/format-color 'cyan "creating ssh tunnel (heartbeating every %d seconds) %s@%s:%s -L %s:%s:%s"
                               hb-sec tunnel-user tunnel-host tunnel-port input-port host port))
    [session tracker]))

(def ssh-tunnel-preferences
  "Configuration parameters to include in the add driver page on drivers that
  support ssh tunnels"
  [{:name         "tunnel-enabled"
    :display-name (deferred-tru "Use an SSH tunnel")
    :placeholder  (deferred-tru "Enable this ssh tunnel?")
    :type         :boolean
    :default      false}
   {:name         "tunnel-host"
    :display-name (deferred-tru "SSH tunnel host")
    :helper-text  (deferred-tru "The hostname that you use to connect to connect to SSH tunnels.")
    :placeholder  "hostname"
    :required     true
    :visible-if   {"tunnel-enabled" true}}
   {:name         "tunnel-port"
    :display-name (deferred-tru "SSH tunnel port")
    :type         :integer
    :default      22
    :required     false
    :visible-if   {"tunnel-enabled" true}}
   {:name         "tunnel-user"
    :display-name (deferred-tru "SSH tunnel username")
    :helper-text  (deferred-tru "The username you use to login to your SSH tunnel.")
    :placeholder  "username"
    :required     true
    :visible-if   {"tunnel-enabled" true}}
   ;; this is entirely a UI flag
   {:name         "tunnel-auth-option"
    :display-name (deferred-tru "SSH Authentication")
    :type         :select
    :options      [{:name (deferred-tru "SSH Key") :value "ssh-key"}
                   {:name (deferred-tru "Password") :value "password"}]
    :default      "ssh-key"
    :visible-if   {"tunnel-enabled" true}}
   {:name         "tunnel-pass"
    :display-name (deferred-tru "SSH tunnel password")
    :type         :password
    :placeholder  "******"
    :visible-if   {"tunnel-auth-option" "password"}}
   {:name         "tunnel-private-key"
    :display-name (deferred-tru "SSH private key to connect to the tunnel")
    :type         :string
    :placeholder  (deferred-tru "Paste the contents of an ssh private key here")
    :required     true
    :visible-if   {"tunnel-auth-option" "ssh-key"}}
   {:name         "tunnel-private-key-passphrase"
    :display-name (deferred-tru "Passphrase for SSH private key")
    :type         :password
    :placeholder  "******"
    :visible-if   {"tunnel-auth-option" "ssh-key"}}])

(defn with-tunnel-config
  "Add preferences for ssh tunnels to a drivers :connection-properties"
  [driver-options]
  (concat driver-options ssh-tunnel-preferences))

(defn use-ssh-tunnel?
  "Is the SSH tunnel currently turned on for these connection details"
  [details]
  (:tunnel-enabled details))

(defn ssh-tunnel-open?
  "Is the SSH tunnel currently open for these connection details?"
  [details]
  (when-let [session (:tunnel-session details)]
    (.isOpen ^ClientSession session)))

(defn include-ssh-tunnel!
  "Updates connection details for a data warehouse to use the ssh tunnel host and port
  For drivers that enter hosts including the protocol (https://host), copy the protocol over as well"
  [details]
  (if (use-ssh-tunnel? details)
    (let [[_ proto host]                           (re-find #"(.*://)?(.*)" (:host details))
          [session ^PortForwardingTracker tracker] (start-ssh-tunnel! (assoc details :host host))
          tunnel-entrance-port                     (.. tracker getBoundAddress getPort)
          tunnel-entrance-host                     (.. tracker getBoundAddress getHostName)
          orig-port                                (:port details)
          details-with-tunnel                      (assoc details
                                                          :port tunnel-entrance-port ;; This parameter is set dynamically when the connection is established
                                                          :host (str proto "localhost") ;; SSH tunnel will always be through localhost
                                                          :orig-port orig-port
                                                          :tunnel-entrance-host tunnel-entrance-host
                                                          :tunnel-entrance-port tunnel-entrance-port ;; the input port is not known until the connection is opened
                                                          :tunnel-enabled true
                                                          :tunnel-session session
                                                          :tunnel-tracker tracker)]
      details-with-tunnel)
    details))

(defmethod driver/incorporate-ssh-tunnel-details :sql-jdbc
  [_ db-details]
  (cond (not (use-ssh-tunnel? db-details))
        ;; no ssh tunnel in use
        db-details
        (ssh-tunnel-open? db-details)
        ;; tunnel in use, and is open
        db-details
        :default
        ;; tunnel in use, and is not open
        (include-ssh-tunnel! db-details)))

(defn close-tunnel!
  "Close a running tunnel session"
  [details]
  (when (and (use-ssh-tunnel? details) (ssh-tunnel-open? details))
    (.close ^ClientSession (:tunnel-session details))))

(defn do-with-ssh-tunnel
  "Starts an SSH tunnel, runs the supplied function with the tunnel open, then closes it"
  [details f]
  (if (use-ssh-tunnel? details)
    (let [details-with-tunnel (include-ssh-tunnel! details)]
      (try
        (log/trace (u/format-color 'cyan "<< OPENED SSH TUNNEL >>"))
        (f details-with-tunnel)
        (finally
          (close-tunnel! details-with-tunnel)
          (log/trace (u/format-color 'cyan "<< CLOSED SSH TUNNEL >>")))))
    (f details)))

(defmacro with-ssh-tunnel
  "Starts an ssh tunnel, and binds the supplied name to a database
  details map with it's values adjusted to use the tunnel"
  [[name details] & body]
  `(do-with-ssh-tunnel ~details
     (fn [~name]
       ~@body)))
