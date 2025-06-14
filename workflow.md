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

1. Tell gemini to give you a diff output for the changes and use `Get-Clipboard | patch -p1` to apply the changes.

## Using clang

```
clang program.cpp -o program.exe -std=c++23
```
