#!/bin/bash
# 推送到 GitHub 的脚本
# 使用方法：
# 1. 先在 GitHub 创建一个新仓库（不要初始化任何文件）
# 2. 把下面的 YOUR_USERNAME 和 YOUR_REPO_NAME 替换成你的 GitHub 用户名和仓库名
# 3. 运行: bash push-to-github.sh

# TODO: 修改这里！
GITHUB_USERNAME="YOUR_USERNAME"
REPO_NAME="legal-ai-consultant"

# 添加远程仓库
git remote add origin "https://github.com/${GITHUB_USERNAME}/${REPO_NAME}.git"

# 重命名分支为 main（GitHub 默认分支）
git branch -M main

# 推送到 GitHub
git push -u origin main

echo "✅ 代码已推送到 GitHub!"
echo "访问: https://github.com/${GITHUB_USERNAME}/${REPO_NAME}"
