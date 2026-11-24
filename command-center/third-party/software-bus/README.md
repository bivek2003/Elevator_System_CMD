Software Bus sources copied without modifications from:
https://github.com/Wallyhoo/CS460_T03_ElevatorProject
Commit: 44402531af830541001fc74b478beeeb62c95def

src/main/java/SoftwareBus/ and src/main/java/Utilities/ contain the original
upstream Java files, including SBMain and DeviceRegister. Do not edit these files.

src/test/java/VirtualRouter/ contains the original upstream Virtual Router files,
unchanged, for isolated distributed testing only. The router is not part of the
production Command Center artifact.

Team 5's startup adapter is in src/main/java/integration/BusConnection.java.
Panel and harness code use the upstream socket protocol. They do not modify or
replace the bus's routing, subscriptions, server, or shutdown behavior.

The original upstream license is preserved in LICENSE. SHA256SUMS records hashes
of the copied sources and license. From command-center/, verify with:

    shasum -a 256 -c third-party/software-bus/SHA256SUMS

This revision returns an assigned-ID reply to REGISTER. Subtopic ALL is a
wildcard on subscriptions, so clients use one queue per topic. RouterLink retries
failed/dropped connections every two seconds. Publications while disconnected
are dropped and the outgoing queue is cleared on reconnect. Client disconnects
can print exceptions and the bus server has no shutdown API.
