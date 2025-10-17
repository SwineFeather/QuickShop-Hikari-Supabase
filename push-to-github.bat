@echo off
echo.
echo Cleaning git history and preparing for push...
echo.

REM Remove old git history (contains credentials)
if exist .git (
    echo Removing old git history...
    rmdir /s /q .git
)

REM Initialize fresh repository
echo Creating fresh git repository...
git init
git add .
git commit -m "Clean up project for public release"

REM Set branch and add remote
git branch -M main
git remote add origin https://github.com/SwineFeather/QuickShop-Hikari-Supabase.git

echo.
echo Done! Now push to GitHub:
echo   git push -u origin main --force
echo.
echo Note: Using --force will overwrite your GitHub repo with this clean version
echo.
pause

