# Dome v4 authoring data

`dome-v4-source.npz` stores deterministic float32 quintic B-spline controls, knots,
end times and streamline metadata. Controls use lossless XOR and byte planes;
`generate_dome_v4.decode_controls` reconstructs the exact original bits.
It is development input, excluded from Minecraft resources and the distribution JAR.

Source SHA256: `15caaf4c32bdc6310d289a5137bdb75808b5226021f7ce3b7f7bce86b1633d4f`.
Final resource SHA256: `53456dfd103eb61f5f7dec5022d33f1d2eaf06adfa2ed584e0d4ffcf27f1fe94`.

Run `python scripts/allaystorm/rebuild_dome_v4.py` from the repository root.
The encoder, exit-plane trim, decoded sampler, continuous kinematic/spacing checks
and closed-flow checks run separately. Output stays in `build/dome-v14/rebuilt`.
A certificate covers its exact decoded asset; rerun production GPU lifecycle,
server replay and compatibility tests before replacing a packaged asset.
See `docs/allay-storm-v14.md` for the runtime handoff and remaining manual checks.
