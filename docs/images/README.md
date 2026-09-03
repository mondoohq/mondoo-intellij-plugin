# Images

The README's overview screenshot lives in [`.github/images/`](../../.github/images),
not here: it is repository furniture rather than user documentation, and keeping it
out of `docs/` means the docs directory is only things people read.

`plugin-overview.png` — the plugin at work in GoLand: findings highlighted in the
editor, grouped by severity and rule in the Mondoo tool window, with the count in the
status bar.

Retake it on the demo file (**Tools → Mondoo Code Security → Open Demo File**) so the
findings are reproducible and nothing real is shown, then downscale it — a retina
capture is around 3000px wide and GitHub renders the README at roughly 900:

```bash
sips --resampleWidth 1600 shot.png --out .github/images/plugin-overview.png
```
