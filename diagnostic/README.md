# TVS Diagnostic

A separate Android APK for stationary tests on the owner's SmartXonnect cluster.
The app compiles the companion repository's scanner, GATT transport, model drivers,
packet builders and navigation bridge from the same source files. The selected
profile controls packet construction. The bench provides manual inputs, including
handshake response and periodic presence on the shared link. It requests Bluetooth
permissions and works offline. It uses neither phone notifications nor real calls.

1. Disconnect the companion app. Open **Connection**, select the cluster model,
   scan and tap the result. Wait for **Ready to write**.
2. Open **Navigation** and tap **Navigation on / apply values**. Try each numbered
   symbol and record the arrow that appears. Change metres to the next junction,
   remaining trip metres and ETA minutes independently. The known control frame
   has one time value. Arrival clock time and full duration are captured as notes.
   **Navigation off** sends the companion bridge's exit frame when available.
3. Open **Calls** to simulate an incoming caller or a message. Start with the
   companion format. The split and single-row options allow comparison without
   editing code. Ending a call tries a blank row for the single-row format.
   A split-frame clear command is unknown, so observe whether it times out.
4. Open **Music** and try play, pause, next and previous. These are simulated
   tracks. The metadata ID and state byte meanings are experimental and editable.
5. In **Incoming**, add a note before pressing a physical cluster button, then
   add the observed result. Each note includes the latest RX frame and can be
   tagged **Important**. Hide routine telemetry to isolate less common IDs.
6. In **Log** or **Incoming**, tap **Export to Downloads**. Android 10+ writes
   directly to Downloads. On Android 8/9 choose Downloads in the document picker.

The on-screen views show a recent tail. Export includes the full local history,
including previous sessions, with timestamps, operation status and full raw
characteristic RX/TX bytes. Transport success reports a BLE write result.
The physical cluster display and LEDs must be confirmed by the rider. The APK
does not capture Android's lower-level HCI traffic. Keep it open during testing
and reconnect manually after a drop. Use invented caller and road details and
review raw captures before sharing.

Build on GitHub Actions with the **Diagnostic APK** workflow and download its
**tvs-diagnostic-debug** artifact.
