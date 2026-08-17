# Remote Probe

The **Remote probe** (bottom of the control screen, connected mode) turns the
app into a protocol sniffer for the receiver. It exists because the fastest
way to learn this receiver's control protocol is to watch what the physical
remote does.

## Background: how the AVR talks

The telnet control connection (port 23) is bidirectional. The app sends
commands and reads replies — but the AVR also **pushes unsolicited status
lines** to the connected client whenever its state changes, no matter *what*
changed it (front panel, IR remote, another app). Every parameter change is
announced as one line containing the exact command string and new value:

```
PSSURLEV MED        <- someone set Sound level compensation to Medium
PSDYNVOL LIT        <- Dynamic Volume -> Light
ZMON                <- main zone powered on
MV52                <- master volume moved
```

Querying a parameter uses the same strings with a trailing `?`
(`PSSURLEV ?` answers `PSSURLEV MED`).

## Using the probe

1. Connect to the receiver and open the control screen.
2. Tap **Probe for 60 s**.
3. Press buttons on the AVR remote (or its front panel). Each reported change
   appears in the log with a `+N.Ns` timestamp since probe start.
4. **Stop** ends the capture early; the log stays until you tap **Clear**.

Notes:

- While a probe runs, the app's own controls are paused (queued) — the AVR
  accepts a single telnet client, so the app never fights itself for the
  socket.
- The log keeps the last 300 lines.
- If the connection drops mid-probe, the log ends with
  `--- connection lost ---`.
- Not every button press produces a line: some buttons only affect transient
  state (menus, one-shot actions), and some parameters are only announced
  when their value actually changes.

## Reading the results

Lines follow the pattern `<PREFIX><VALUE>`:

- The **prefix** is the command name (`PSSURLEV`, `PSDYNVOL`, `ZM`, ...).
  `PS` = sound parameter, `SS` = surround/speaker setup, `SI` = input,
  `MS` = sound mode, `MV` = master volume, `ZM` = main-zone power.
- The **value** is what to send after the prefix to set it. To set the value
  yourself: send `<PREFIX> <VALUE>` (no `?`). If the AVR accepts it, it
  echoes the line back; if a token is wrong the AVR stays **silently**
  unresponsive — no error, no NAK.
- Watch out for **abbreviated tokens** on this platform: Light = `LIT`,
  Medium = `MED`, Heavy = `HEV`. Long forms (`LIGHT`, `HEAVY`, `ON` for
  Dynamic Volume) are silently rejected.

## Discovered with this method (verified on Marantz CINEMA 50)

| Command | Values | Notes |
|---|---|---|
| `PSDYNVOL` | `OFF / LIT / MED / HEV` | Dynamic Volume; abbreviated tokens only |
| `PSSURLEV` | `OFF / LIT / MED / HEV` | Sound level compensation (under Dynamic EQ); writes rejected while DynEQ is off |
| `PSDYNEQ` | `ON / OFF` | Dynamic EQ — no levels exist |
| `PSREFLEV` | `0 / 5 / 10 / 15` | DynEQ Reference Level Offset; writes rejected while DynEQ is off |
| `PSMULTEQ:` | `AUDYSSEY / FLAT / OFF` | MultEQ curve (colon, no space) |
| `SPPR` | `1 / 2` | Speaker preset; writes ignored while powered off |
| `ZM` | `ON / OFF` | Main zone power |

## Recipe for discovering a new command

1. Start the probe.
2. Change exactly ONE setting on the remote; note the line that appears.
3. Repeat for every value of that setting to collect the full token set.
4. Verify control: set a value via the probe's findings from any telnet
   client and re-query with `?` — the readback must follow.
5. Restore the original value.
