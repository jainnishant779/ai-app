# Nishu Android app

Build instructions live in [docs/BUILD-SPEC.md](docs/BUILD-SPEC.md). Read it fully first and follow its milestones (M0 to M14, with MU after M4) in order. Do not skip a milestone's Verify step.

- UI visual target: `docs/design/ui-reference.png`. Look at it before and after each UI phase.
- Read `docs/CONTRACT.md` fully before touching prompt, tokenizer or engine code.
- Where BUILD-SPEC.md and the other docs disagree, BUILD-SPEC.md wins.
- Python: run from PowerShell as `$env:PYTHONHOME=$null; py -3.12 <script>`. Bash `python` is broken on this machine.
- Commit locally after each milestone. Never push.
