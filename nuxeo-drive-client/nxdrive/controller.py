"""Main API to perform Nuxeo Drive operations"""

import os
import sys
from urllib import quote
from threading import local
import subprocess
from datetime import datetime
from datetime import timedelta
import calendar

from cookielib import CookieJar

from sqlalchemy.orm.exc import NoResultFound
from sqlalchemy import asc
from sqlalchemy import or_

import nxdrive
from nxdrive.client import Unauthorized
from nxdrive.client import LocalClient
from nxdrive.client import RemoteFileSystemClient
from nxdrive.client import RemoteDocumentClient
from nxdrive.client import NotFound
from nxdrive.model import init_db
from nxdrive.model import DeviceConfig
from nxdrive.model import ServerBinding
from nxdrive.model import LastKnownState
from nxdrive.synchronizer import Synchronizer
from nxdrive.synchronizer import POSSIBLE_NETWORK_ERROR_TYPES
from nxdrive.logging_config import get_logger
from nxdrive.utils import normalized_path
from nxdrive.utils import safe_long_path
from nxdrive.utils import encrypt
from nxdrive.utils import decrypt


log = get_logger(__name__)


def default_nuxeo_drive_folder():
    """Find a reasonable location for the root Nuxeo Drive folder

    This folder is user specific, typically under the home folder.
    """
    if sys.platform == "win32":
        # WARNING: it's important to check `Documents` first as under Windows 7
        # there also exists a `My Documents` folder invisible in the explorer
        # and cmd / powershell but visible from Python
        documents = os.path.expanduser(ur'~\Documents')
        my_documents = os.path.expanduser(ur'~\My Documents')
        if os.path.exists(documents):
            # Regular location for documents under Windows 7 and up
            return os.path.join(documents, u'Nuxeo Drive')
        elif os.path.exists(my_documents):
            # Compat for Windows XP
            return os.path.join(my_documents, u'Nuxeo Drive')

    # Fallback to home folder otherwise
    return os.path.join(os.path.expanduser(u'~'), u'Nuxeo Drive')


class ServerBindingSettings(object):
    """Summarize server binding settings"""

    def __init__(self, server_url=None, username=None, password=None,
                 local_folder=None, initialized=False,
                 pwd_update_required=False):
        self.server_url = server_url
        self.username = username
        self.password = password
        self.local_folder = local_folder
        self.initialized = initialized
        self.pwd_update_required = pwd_update_required

    def __repr__(self):
        return ("ServerBindingSettings<server_url=%s, username=%s, "
                "local_folder=%s, initialized=%r, pwd_update_required=%r>") % (
                    self.server_url, self.username, self.local_folder,
                    self.initialized, self.pwd_update_required)


class ProxySettings(object):
    """Summarize HTTP proxy settings"""

    def __init__(self, config='System', proxy_type=None,
                 server=None, port=None,
                 authenticated=False, username=None, password=None,
                 exceptions=None):
        self.config = config
        self.proxy_type = proxy_type
        self.server = server
        self.port = port
        self.authenticated = authenticated
        self.username = username
        self.password = password
        self.exceptions = exceptions

    def __repr__(self):
        return ("ProxySettings<config=%s, proxy_type=%s, server=%s, port=%s, "
                "authenticated=%r, username=%s, password=%s, "
                "exceptions=%s>") % (
                    self.config, self.proxy_type, self.server, self.port,
                    self.authenticated, self.username, self.password,
                    self.exceptions)


class Controller(object):
    """Manage configuration and perform Nuxeo Drive Operations

    This class is thread safe: instance can be shared by multiple threads
    as DB sessions and Nuxeo clients are thread locals.
    """

    # Used for binding server / roots and managing tokens
    remote_doc_client_factory = RemoteDocumentClient

    # Used for FS synchronization operations
    remote_fs_client_factory = RemoteFileSystemClient

    def __init__(self, config_folder, echo=None, poolclass=None,
                 handshake_timeout=60, timeout=20, page_size=None):
        # Log the installation location for debug
        nxdrive_install_folder = os.path.dirname(nxdrive.__file__)
        nxdrive_install_folder = os.path.realpath(nxdrive_install_folder)
        log.debug("nxdrive installed in '%s'", nxdrive_install_folder)

        # Log the configuration location for debug
        config_folder = os.path.expanduser(config_folder)
        self.config_folder = os.path.realpath(config_folder)
        if not os.path.exists(self.config_folder):
            os.makedirs(self.config_folder)
        log.debug("nxdrive configured in '%s'", self.config_folder)

        if echo is None:
            echo = os.environ.get('NX_DRIVE_LOG_SQL', None) is not None
        self.handshake_timeout = handshake_timeout
        self.timeout = timeout

        # Handle connection to the local Nuxeo Drive configuration and
        # metadata sqlite database.
        self._engine, self._session_maker = init_db(
            self.config_folder, echo=echo, poolclass=poolclass)

        # Thread-local storage for the remote client cache
        self._local = local()
        self._client_cache_timestamps = dict()

        self._remote_error = None

        device_config = self.get_device_config()
        self.device_id = device_config.device_id

        # HTTP proxy settings
        self.proxies = None
        self.proxy_exceptions = None
        self.refresh_proxies(device_config=device_config)

        self.synchronizer = Synchronizer(self, page_size=page_size)

        # Make all the automation client related to this controller
        # share cookies using threadsafe jar
        self.cookie_jar = CookieJar()

    def get_session(self):
        """Reuse the thread local session for this controller

        Using the controller in several thread should be thread safe as long as
        this method is always called to fetch the session instance.
        """
        return self._session_maker()

    def get_device_config(self, session=None):
        """Fetch the singleton configuration object for this device"""
        if session is None:
            session = self.get_session()
        try:
            return session.query(DeviceConfig).one()
        except NoResultFound:
            device_config = DeviceConfig()  # generate a unique device id
            session.add(device_config)
            session.commit()
            return device_config

    def get_proxy_settings(self, device_config=None):
        """Fetch proxy settings from database"""
        dc = (self.get_device_config() if device_config is None
              else device_config)
        # Decrypt password with token as the secret
        # TODO: handle case where no server bindings and/or no token
        # (possibly after token revocation).
        # For now password is stored as plain text.
        token = self.get_token()
        if token is not None:
            password = decrypt(dc.proxy_password, token)
        else:
            password = ''
        return ProxySettings(config=dc.proxy_config,
                                       proxy_type=dc.proxy_type,
                                       server=dc.proxy_server,
                                       port=dc.proxy_port,
                                       authenticated=dc.proxy_authenticated,
                                       username=dc.proxy_username,
                                       password=password,
                                       exceptions=dc.proxy_exceptions)

    def set_proxy_settings(self, proxy_settings):
        session = self.get_session()
        device_config = self.get_device_config(session)

        device_config.proxy_config = proxy_settings.config
        device_config.proxy_type = proxy_settings.proxy_type
        device_config.proxy_server = proxy_settings.server
        device_config.proxy_port = proxy_settings.port
        device_config.proxy_exceptions = proxy_settings.exceptions
        device_config.proxy_authenticated = proxy_settings.authenticated
        device_config.proxy_username = proxy_settings.username
        # Encrypt password with token as the secret
        # TODO: handle case where no server bindings and/or no token
        # (possibly after token revocation).
        # For now store password as plain text.
        token = self.get_token(session)
        if token is None:
            raise ValueError('No token!')
        password = encrypt(proxy_settings.password, token)
        device_config.proxy_password = password

        session.commit()
        self.invalidate_client_cache()

    def refresh_proxies(self, proxy_settings=None, device_config=None):
        """Return a pair containing proxy strings and exceptions list"""
        # If no proxy settings passed fetch them from database
        proxy_settings = (proxy_settings if proxy_settings is not None
                          else self.get_proxy_settings(
                                                device_config=device_config))
        if proxy_settings.config == 'None':
            # No proxy, return an empty dictionary to disable
            # default proxy detection
            self.proxies = {}
            self.proxy_exceptions = None
        elif proxy_settings.config == 'System':
            # System proxy, return None to use default proxy detection
            self.proxies = None
            self.proxy_exceptions = None
        else:
            # Manual proxy settings, build proxy string and exceptions list
            if proxy_settings.authenticated:
                proxy_string = ("%s://%s:%s@%s:%s") % (
                                    proxy_settings.proxy_type,
                                    proxy_settings.username,
                                    proxy_settings.password,
                                    proxy_settings.server,
                                    proxy_settings.port)
            else:
                proxy_string = ("%s://%s:%s") % (
                                    proxy_settings.proxy_type,
                                    proxy_settings.server,
                                    proxy_settings.port)
            self.proxies = {proxy_settings.proxy_type: proxy_string}
            if proxy_settings.exceptions.strip():
                self.proxy_exceptions = [e.strip() for e in
                                    proxy_settings.exceptions.split(',')]
            else:
                self.proxy_exceptions = None

    def get_server_binding(self, local_folder, raise_if_missing=False,
                           session=None):
        """Find the ServerBinding instance for a given local_folder"""
        local_folder = normalized_path(local_folder)
        if session is None:
            session = self.get_session()
        try:
            return session.query(ServerBinding).filter(
                ServerBinding.local_folder == local_folder).one()
        except NoResultFound:
            if raise_if_missing:
                raise RuntimeError(
                    "Folder '%s' is not bound to any Nuxeo server"
                    % local_folder)
            return None

    def list_server_bindings(self, session=None):
        if session is None:
            session = self.get_session()
        return session.query(ServerBinding).all()

    def get_token(self, session=None):
        if session is None:
            session = self.get_session()
        server_bindings = self.list_server_bindings(session)
        if server_bindings:
            sb = server_bindings[0]
            if sb.remote_token is not None:
                return sb.remote_token
        else:
            return None

    def get_server_binding_settings(self):
        """Fetch server binding settings from database"""
        server_bindings = self.list_server_bindings()
        if not server_bindings:
            return ServerBindingSettings(
                local_folder=default_nuxeo_drive_folder())
        else:
            # TODO: handle multiple server bindings, for now take the first one
            # See https://jira.nuxeo.com/browse/NXP-12716
            sb = server_bindings[0]
            return ServerBindingSettings(server_url=sb.server_url,
                            username=sb.remote_user,
                            local_folder=sb.local_folder,
                            initialized=True,
                            pwd_update_required=sb.has_invalid_credentials())

    def stop(self):
        """Stop the Nuxeo Drive synchronization thread

        As the process asking the synchronization to stop might not be the same
        as the process running the synchronization (especially when used from
        the commandline without the graphical user interface and its tray icon
        menu) we use a simple empty marker file a cross platform way to pass
        the stop message between the two.

        """
        pid = self.synchronizer.check_running(process_name="sync")
        if pid is not None:
            # Create a stop file marker for the running synchronization
            # process
            log.info("Telling synchronization process %d to stop." % pid)
            stop_file = os.path.join(self.config_folder, "stop_%d" % pid)
            open(safe_long_path(stop_file), 'wb').close()
        else:
            log.info("No running synchronization process to stop.")

    def children_states(self, folder_path):
        """List the status of the children of a folder

        The state of the folder is a summary of their descendant rather
        than their own instric synchronization step which is of little
        use for the end user.

        """
        session = self.get_session()
        # Find the server binding for this absolute path
        try:
            binding, path = self._binding_path(folder_path, session=session)
        except NotFound:
            return []

        try:
            folder_state = session.query(LastKnownState).filter_by(
                local_folder=binding.local_folder,
                local_path=path,
            ).one()
        except NoResultFound:
            return []

        states = self._pair_states_recursive(session, folder_state)

        return [(os.path.basename(s.local_path), pair_state)
                for s, pair_state in states
                if s.local_parent_path == path]

    def _pair_states_recursive(self, session, doc_pair):
        """Recursive call to collect pair state under a given location."""
        if not doc_pair.folderish:
            return [(doc_pair, doc_pair.pair_state)]

        if doc_pair.local_path is not None and doc_pair.remote_ref is not None:
            f = or_(
                LastKnownState.local_parent_path == doc_pair.local_path,
                LastKnownState.remote_parent_ref == doc_pair.remote_ref,
            )
        elif doc_pair.local_path is not None:
            f = LastKnownState.local_parent_path == doc_pair.local_path
        elif doc_pair.remote_ref is not None:
            f = LastKnownState.remote_parent_ref == doc_pair.remote_ref
        else:
            raise ValueError("Illegal state %r: at least path or remote_ref"
                             " should be not None." % doc_pair)

        children_states = session.query(LastKnownState).filter_by(
            local_folder=doc_pair.local_folder).filter(f).order_by(
                asc(LastKnownState.local_name),
                asc(LastKnownState.remote_name),
            ).all()

        results = []
        for child_state in children_states:
            sub_results = self._pair_states_recursive(session, child_state)
            results.extend(sub_results)

        # A folder stays synchronized (or unknown) only if all the descendants
        # are themselfves synchronized.
        pair_state = doc_pair.pair_state
        for _, sub_pair_state in results:
            if sub_pair_state != 'synchronized':
                pair_state = 'children_modified'
            break
        # Pre-pend the folder state to the descendants
        return [(doc_pair, pair_state)] + results

    def _binding_path(self, local_path, session=None):
        """Find a server binding and relative path for a given FS path"""
        local_path = normalized_path(local_path)

        # Check exact binding match
        binding = self.get_server_binding(local_path, session=session,
            raise_if_missing=False)
        if binding is not None:
            return binding, u'/'

        # Check for bindings that are prefix of local_path
        session = self.get_session()
        all_bindings = session.query(ServerBinding).all()
        matching_bindings = [sb for sb in all_bindings
                             if local_path.startswith(
                                sb.local_folder + os.path.sep)]
        if len(matching_bindings) == 0:
            raise NotFound("Could not find any server binding for "
                               + local_path)
        elif len(matching_bindings) > 1:
            raise RuntimeError("Found more than one binding for %s: %r" % (
                local_path, matching_bindings))
        binding = matching_bindings[0]
        path = local_path[len(binding.local_folder):]
        path = path.replace(os.path.sep, u'/')
        return binding, path

    def bind_server(self, local_folder, server_url, username, password):
        """Bind a local folder to a remote nuxeo server"""
        session = self.get_session()
        local_folder = normalized_path(local_folder)
        if not os.path.exists(local_folder):
            os.makedirs(local_folder)

        self.register_folder_link(local_folder)

        # check the connection to the server by issuing an authentication
        # request
        server_url = self._normalize_url(server_url)
        nxclient = self.remote_doc_client_factory(
            server_url, username, self.device_id,
            proxies=self.proxies, proxy_exceptions=self.proxy_exceptions,
            password=password, timeout=self.handshake_timeout)
        token = nxclient.request_token()
        if token is not None:
            # The server supports token based identification: do not store the
            # password in the DB
            password = None
        try:
            server_binding = session.query(ServerBinding).filter(
                ServerBinding.local_folder == local_folder).one()
            if (server_binding.remote_user != username
                or server_binding.server_url != server_url):
                raise RuntimeError(
                    "%s is already bound to '%s' with user '%s'" % (
                        local_folder, server_binding.server_url,
                        server_binding.remote_user))

            if token is None and server_binding.remote_password != password:
                # Update password info if required
                server_binding.remote_password = password
                log.info("Updating password for user '%s' on server '%s'",
                        username, server_url)

            if token is not None and server_binding.remote_token != token:
                log.info("Updating token for user '%s' on server '%s'",
                        username, server_url)
                # Update the token info if required
                server_binding.remote_token = token

                # Ensure that the password is not stored in the DB
                if server_binding.remote_password is not None:
                    server_binding.remote_password = None

        except NoResultFound:
            log.info("Binding '%s' to '%s' with account '%s'",
                     local_folder, server_url, username)
            server_binding = ServerBinding(local_folder, server_url, username,
                                           remote_password=password,
                                           remote_token=token)
            session.add(server_binding)

            # Creating the toplevel state for the server binding
            local_client = LocalClient(server_binding.local_folder)
            local_info = local_client.get_info(u'/')

            remote_client = self.get_remote_fs_client(server_binding)
            remote_info = remote_client.get_filesystem_root_info()

            state = LastKnownState(server_binding.local_folder,
                                   local_info=local_info,
                                   local_state='synchronized',
                                   remote_info=remote_info,
                                   remote_state='synchronized')
            session.add(state)

        session.commit()
        return server_binding

    def unbind_server(self, local_folder):
        """Remove the binding to a Nuxeo server

        Local files are not deleted"""
        session = self.get_session()
        local_folder = normalized_path(local_folder)
        binding = self.get_server_binding(local_folder, raise_if_missing=True,
                                          session=session)

        # Revoke token if necessary
        if binding.remote_token is not None:
            try:
                nxclient = self.remote_doc_client_factory(
                        binding.server_url,
                        binding.remote_user,
                        self.device_id,
                        proxies=self.proxies,
                        proxy_exceptions=self.proxy_exceptions,
                        token=binding.remote_token,
                        timeout=self.timeout)
                log.info("Revoking token for '%s' with account '%s'",
                         binding.server_url, binding.remote_user)
                nxclient.revoke_token()
            except POSSIBLE_NETWORK_ERROR_TYPES:
                log.warning("Could not connect to server '%s' to revoke token",
                            binding.server_url)
            except Unauthorized:
                # Token is already revoked
                pass

        # Invalidate client cache
        self.invalidate_client_cache(binding.server_url)

        # Delete binding info in local DB
        log.info("Unbinding '%s' from '%s' with account '%s'",
                 local_folder, binding.server_url, binding.remote_user)
        session.delete(binding)
        session.commit()

    def unbind_all(self):
        """Unbind all server and revoke all tokens

        This is useful for cleanup in integration test code.
        """
        session = self.get_session()
        for sb in session.query(ServerBinding).all():
            self.unbind_server(sb.local_folder)

    def bind_root(self, local_folder, remote_ref, repository='default',
                  session=None):
        """Bind local root to a remote root (folderish document in Nuxeo).

        local_folder must be already bound to an existing Nuxeo server.

        remote_ref must be the IdRef or PathRef of an existing folderish
        document on the remote server bound to the local folder.

        """
        session = self.get_session() if session is None else session
        local_folder = normalized_path(local_folder)
        server_binding = self.get_server_binding(
            local_folder, raise_if_missing=True, session=session)

        nxclient = self.get_remote_doc_client(server_binding,
            repository=repository)

        # Register the root on the server
        nxclient.register_as_root(remote_ref)

    def unbind_root(self, local_folder, remote_ref, repository='default',
                    session=None):
        """Remove binding to remote folder"""
        session = self.get_session() if session is None else session
        server_binding = self.get_server_binding(
            local_folder, raise_if_missing=True, session=session)

        nxclient = self.get_remote_doc_client(server_binding,
            repository=repository)

        # Unregister the root on the server
        nxclient.unregister_as_root(remote_ref)

    def list_pending(self, limit=100, local_folder=None, ignore_in_error=None,
                     session=None):
        """List pending files to synchronize, ordered by path

        Ordering by path makes it possible to synchronize sub folders content
        only once the parent folders have already been synchronized.

        If ingore_in_error is not None and is a duration in second, skip pair
        states that have recently triggered a synchronization error.
        """
        if session is None:
            session = self.get_session()

        predicates = [LastKnownState.pair_state != 'synchronized']
        if local_folder is not None:
            predicates.append(LastKnownState.local_folder == local_folder)

        if ignore_in_error is not None and ignore_in_error > 0:
            max_date = datetime.utcnow() - timedelta(seconds=ignore_in_error)
            predicates.append(or_(
                LastKnownState.last_sync_error_date == None,
                LastKnownState.last_sync_error_date < max_date))

        return session.query(LastKnownState).filter(
            *predicates
        ).order_by(
            # Ensure that newly created remote folders will be synchronized
            # before their children while keeping a fixed named based
            # deterministic ordering to make the tests readable
            asc(LastKnownState.remote_parent_path),
            asc(LastKnownState.remote_name),
            asc(LastKnownState.remote_ref),

            # Ensure that newly created local folders will be synchronized
            # before their children
            asc(LastKnownState.local_path)
        ).limit(limit).all()

    def next_pending(self, local_folder=None, session=None):
        """Return the next pending file to synchronize or None"""
        pending = self.list_pending(limit=1, local_folder=local_folder,
                                    session=session)
        return pending[0] if len(pending) > 0 else None

    def _get_client_cache(self):
        if not hasattr(self._local, 'remote_clients'):
            self._local.remote_clients = dict()
        return self._local.remote_clients

    def get_remote_fs_client(self, server_binding):
        """Return a client for the FileSystem abstraction."""
        cache = self._get_client_cache()
        sb = server_binding
        cache_key = (sb.server_url, sb.remote_user, self.device_id)
        remote_client_cache = cache.get(cache_key)
        if remote_client_cache is not None:
            remote_client = remote_client_cache[0]
            timestamp = remote_client_cache[1]
        client_cache_timestamp = self._client_cache_timestamps.get(cache_key)

        if remote_client_cache is None or timestamp < client_cache_timestamp:
            remote_client = self.remote_fs_client_factory(
                sb.server_url, sb.remote_user, self.device_id,
                proxies=self.proxies, proxy_exceptions=self.proxy_exceptions,
                password=sb.remote_password, token=sb.remote_token,
                timeout=self.timeout, cookie_jar=self.cookie_jar)
            if client_cache_timestamp is None:
                client_cache_timestamp = 0
                self._client_cache_timestamps[cache_key] = 0
            cache[cache_key] = remote_client, client_cache_timestamp
        # Make it possible to have the remote client simulate any kind of
        # failure: this is useful for ensuring that cookies used for load
        # balancer affinity (e.g. AWSELB) are shared by all the automation
        # clients managed by a given controller
        remote_client.make_raise(self._remote_error)
        return remote_client

    def get_remote_doc_client(self, server_binding, repository='default',
                              base_folder=None):
        """Return an instance of Nuxeo Document Client"""
        sb = server_binding
        return self.remote_doc_client_factory(
            sb.server_url, sb.remote_user, self.device_id,
            proxies=self.proxies, proxy_exceptions=self.proxy_exceptions,
            password=sb.remote_password, token=sb.remote_token,
            repository=repository, base_folder=base_folder,
            timeout=self.timeout, cookie_jar=self.cookie_jar)

    def get_remote_client(self, server_binding, repository='default',
                          base_folder=None):
        # Backward compat
        return self.get_remote_doc_client(server_binding,
            repository=repository, base_folder=base_folder)

    def invalidate_client_cache(self, server_url=None):
        for key in self._client_cache_timestamps:
            if server_url is None or key[0] == server_url:
                now = datetime.utcnow().utctimetuple()
                self._client_cache_timestamps[key] = calendar.timegm(now)
        # Re-fetch HTTP proxy settings
        self.refresh_proxies()

    def get_state(self, server_url, remote_ref):
        """Find a pair state for the provided remote document identifiers."""
        server_url = self._normalize_url(server_url)
        session = self.get_session()
        try:
            states = session.query(LastKnownState).filter_by(
                remote_ref=remote_ref,
            ).all()
            for state in states:
                if (state.server_binding.server_url == server_url):
                    return state
        except NoResultFound:
            return None

    def get_state_for_local_path(self, local_os_path):
        """Find a DB state from a local filesystem path"""
        session = self.get_session()
        sb, local_path = self._binding_path(local_os_path, session=session)
        return session.query(LastKnownState).filter_by(
            local_folder=sb.local_folder, local_path=local_path).one()

    def launch_file_editor(self, server_url, remote_ref):
        """Find the local file if any and start OS editor on it."""

        state = self.get_state(server_url, remote_ref)
        if state is None:
            # TODO: synchronize to a dedicated special root for one time edit
            log.warning('Could not find local file for server_url=%s '
                        'and remote_ref=%s', server_url, remote_ref)
            return

        # TODO: check synchronization of this state first

        # Find the best editor for the file according to the OS configuration
        file_path = state.get_local_abspath()
        self.open_local_file(file_path)

    def open_local_file(self, file_path):
        """Launch the local OS program on the given file / folder."""
        log.debug('Launching editor on %s', file_path)
        if sys.platform == 'win32':
            os.startfile(file_path)
        elif sys.platform == 'darwin':
            subprocess.Popen(['open', file_path])
        else:
            try:
                subprocess.Popen(['xdg-open', file_path])
            except OSError:
                # xdg-open should be supported by recent Gnome, KDE, Xfce
                log.error("Failed to find and editor for: '%s'", file_path)

    def make_remote_raise(self, error):
        """Helper method to simulate network failure for testing"""
        self._remote_error = error

    def dispose(self):
        """Release all database resources"""
        self.get_session().close_all()
        self._engine.pool.dispose()

    def _normalize_url(self, url):
        """Ensure that user provided url always has a trailing '/'"""
        if url is None or not url:
            raise ValueError("Invalid url: %r" % url)
        if not url.endswith(u'/'):
            return url + u'/'
        return url

    def register_folder_link(self, folder_path):
        if sys.platform == 'darwin':
            self.register_folder_link_darwin(folder_path)
        # TODO: implement Windows and Linux support here

    def register_folder_link_darwin(self, folder_path):
        try:
            from LaunchServices import LSSharedFileListCreate
            from LaunchServices import kLSSharedFileListFavoriteItems
            from LaunchServices import LSSharedFileListInsertItemURL
            from LaunchServices import kLSSharedFileListItemBeforeFirst
            from LaunchServices import CFURLCreateWithString
        except ImportError:
            log.warning("PyObjC package is not installed:"
                        " skipping favorite link creation")
            return
        folder_path = normalized_path(folder_path)
        folder_name = os.path.basename(folder_path)

        lst = LSSharedFileListCreate(None, kLSSharedFileListFavoriteItems,
                                     None)
        if lst is None:
            log.warning("Could not fetch the Finder favorite list.")
            return

        url = CFURLCreateWithString(None, "file://" + quote(folder_path), None)
        if url is None:
            log.warning("Could not generate valid favorite URL for: %s",
                folder_path)
            return

        # Register the folder as favorite if not already there
        item = LSSharedFileListInsertItemURL(
            lst, kLSSharedFileListItemBeforeFirst, folder_name, None, url,
            {}, [])
        if item is not None:
            log.debug("Registered new favorite in Finder for: %s", folder_path)
