"""Forwards one loopback TCP port to the local Cassandra service.

Cassandra publishes no host port: administration goes through `docker exec`, so a
freshly created cluster has no host-reachable default-login window. An application
running outside the Compose network still needs the state store, so this forwarder
starts only after bootstrap has replaced the default login, and carries bytes
without reading them — authentication and TLS stay end to end with Cassandra.

Local development and CI only. Nothing in a cluster uses this.
"""

import socket
import socketserver
import threading

UPSTREAM = ("cassandra", 9042)


def copy(source, target):
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            target.sendall(data)
    except OSError:
        pass
    finally:
        try:
            target.shutdown(socket.SHUT_WR)
        except OSError:
            pass


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            upstream = socket.create_connection(UPSTREAM, timeout=5)
        except OSError:
            return
        upstream.settimeout(None)
        outbound = threading.Thread(target=copy, args=(self.request, upstream), daemon=True)
        outbound.start()
        copy(upstream, self.request)
        outbound.join()
        upstream.close()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    Server(("0.0.0.0", 9042), Handler).serve_forever()
