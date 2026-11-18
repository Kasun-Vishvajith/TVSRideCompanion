package dev.raider.companion;

import java.util.*;

/** Bounded receive-only experiment. Records transitions, including a press that returns to baseline. */
final class Discovery {
    private final Map<Integer, byte[]> previous = new HashMap<>();
    private final Map<Integer, Long> received = new HashMap<>();
    private final LinkedHashMap<String, String> changes = new LinkedHashMap<>();
    private final Set<Integer> seenAfterArm = new HashSet<>();
    private Set<Integer> baselineIds = new HashSet<>();
    private long until;
    private int transitions;
    synchronized void reset() {
        previous.clear(); received.clear(); changes.clear(); seenAfterArm.clear(); baselineIds.clear(); until = 0; transitions = 0;
    }
    synchronized void disconnected(long now) {
        // Preserve the experiment for saving, but never compare a new link against an old baseline.
        if (until != 0) until = Math.min(until, Math.max(1, now - 1));
        previous.clear(); received.clear();
    }
    synchronized boolean arm(long now) {
        previous.keySet().removeIf(id -> now - received.getOrDefault(id, -100_000L) > 10_000);
        if (previous.isEmpty()) return false;
        changes.clear(); seenAfterArm.clear(); baselineIds = new HashSet<>(previous.keySet());
        transitions = 0; until = now + 15_000; return true;
    }
    synchronized void frame(byte[] b, long now) {
        // Never collect startup/identity, credentials, text or arbitrary unknown packet payloads.
        boolean standard = Protocol.telemetry(b);
        boolean unknown12 = b.length == 20 && Protocol.u(b, 0) == 0x5a && Protocol.u(b, 1) == 0x12 && Protocol.u(b, 19) == 255;
        if (!standard && !unknown12) return;
        int id = Protocol.u(b, 1);
        received.put(id, now);
        byte[] old = previous.put(id, b.clone());
        if (until == 0 || now > until) return;
        seenAfterArm.add(id);
        if (old == null) {
            changes.put("new:" + id, String.format(Locale.ROOT, "5A%02X appeared: no earlier baseline for this frame group", id));
            transitions++;
            return;
        }
        for (int i = 2; i < 18; i++) {
            if (old[i] == b[i] || !Protocol.discoveryByte(id, i)) continue;
            String key = id + ":" + i;
            if (changes.size() < 80 || changes.containsKey(key)) changes.put(key,
                String.format(Locale.ROOT, "5A%02X byte[%d]: %02X → %02X (xor %02X)%s", id, i,
                    old[i] & 255, b[i] & 255, (old[i] ^ b[i]) & 255, unknown12 ? " [unverified frame]" : ""));
            transitions++;
        }
    }
    synchronized boolean active(long now) { return until != 0 && now <= until; }
    synchronized boolean started() { return until != 0; }
    synchronized String report(long now) {
        if (until == 0) return "Ready for a baseline. Connect the Raider first.";
        StringBuilder out = new StringBuilder(active(now) ? "Listening — press the bike button now.\n" : "Capture finished.\n");
        if (seenAfterArm.isEmpty()) return out.append("No frames received. No comparison available.").toString();
        if (!seenAfterArm.containsAll(baselineIds)) out.append("Some baseline frame groups were not received.\n");
        if (changes.isEmpty()) out.append("No unexplained byte changes detected.");
        else {
            out.append(transitions).append(" unexplained transitions. Did you change something?\n\n");
            for (String line : changes.values()) out.append(line).append('\n');
        }
        return out.toString();
    }
}
