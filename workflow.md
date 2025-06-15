## Merging feature branches without cluttering the history

**Using `git merge --squash`**

**1. Ensure Your `main` Branch is Up-to-Date:**

```bash
git checkout main
git pull origin main
```

**2. Perform the Squash Merge:**

```bash
git merge --squash feature-branch-name
```

**3. Commit the Squashed Changes:**

After the `git merge --squash` command, Git will have staged all the changes from `feature-branch-name` into your `main` branch, but it hasn't committed them yet. You'll need to create a new commit.

```bash
git commit
```

## Working with gemini chat

Tell gemini to give you a diff output for the changes and use `Get-Clipboard | patch -p1` to apply the changes.

**Useful System Prompt for One-Off Scripts**

- Don't add comments for the changes you made like `//modified: ...` or `//Here's the change` etc.
- Only make changes directly related to the task and nothing else. Keep everything else intact.
- Return the whole code as modified.

## Using clang

```
clang program.cpp -o program.exe -std=c++23
```
