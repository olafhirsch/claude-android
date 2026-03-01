# claude-android

Personal [Claude Code](https://claude.ai/code) toolkit for Android development — commands and skills in one place.

## What's included

### Commands (`/slash` commands)

| Command | What it does |
|---|---|
| `/android-run` | Build, install, and launch the app on the emulator |
| `/android-build` | Build the debug APK (`assembleDebug`) |
| `/android-check` | Full quality gate: build → unit tests → lint |
| `/android-lint` | Run lint and report issues by severity |
| `/android-test` | Run unit tests and report pass/fail counts |
| `/android-logcat` | Capture and analyze logcat output from the running app |

### Skills (context-triggered)

| Skill | Triggers on |
|---|---|
| `android-dev` | "create an Android app", "add Jetpack Compose screen", "set up MVVM", "create a ViewModel", "add Room database", "configure Hilt", and more |

## Installation

```bash
git clone https://github.com/olafhirsch/claude-android ~/Documents/Projects/claude-android
cd ~/Documents/Projects/claude-android
bash install.sh
```

Restart Claude Code. Commands and skills are active from the next session.

## Updating

```bash
cd ~/Documents/Projects/claude-android
git pull
bash install.sh
```

## Modifying a command or skill

Edit the file in this repo, then run `bash install.sh` to push the changes into `~/.claude/`.

```
claude-android/
  commands/
    android-run.md
    android-build.md
    android-check.md
    android-lint.md
    android-test.md
    android-logcat.md
  skills/
    android-dev/
      SKILL.md
      references/
      examples/
  install.sh
  README.md
```

## Adding a new command

Create a new `.md` file in `commands/` with a YAML frontmatter block:

```markdown
---
allowed-tools: Bash(adb*), Bash(./gradlew*)
description: One-line description shown in the /help list
---

## My Command

Instructions for Claude...
```

Then run `bash install.sh`.

## Adding a new skill

Create a directory under `skills/` with a `SKILL.md`:

```markdown
---
name: my-skill
description: This skill should be used when the user asks to "..."
---

# My Skill

...
```

Then run `bash install.sh`.
