Software Bus sources copied without modifications from:
https://github.com/Wallyhoo/CS460_T03_ElevatorProject
Commit: dcc3948d101a8294c71e93eb9737a214ab39d480

src/main/java/SoftwareBus/ and src/main/java/Utilities/ contain the original
upstream Java files, including SBMain. Do not edit these files.

src/test/java/VirtualRouter/ contains the original upstream Virtual Router files,
unchanged, for isolated distributed testing only. The router is not part of the
production Command Center artifact.

Team 5's startup adapter is in src/main/java/integration/BusConnection.java.
Panel and harness code use the upstream socket protocol. They do not modify or
replace the bus's routing, subscriptions, server, or shutdown behavior.

The original upstream license is preserved in LICENSE. SHA256SUMS records hashes
of the copied sources and license. From command-center/, verify with:

    shasum -a 256 -c third-party/software-bus/SHA256SUMS

Upstream limitations remain unchanged: client disconnects can print exceptions,
there is no bus shutdown API, and RouterLink currently makes one connection
attempt despite its comment mentioning reconnection. Start the router first;
restart the subsystem if its bus-to-router connection is lost.
