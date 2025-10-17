# Contributing

Thanks for considering contributing! Here's what you need to know.

## Reporting Bugs

Check if the issue already exists first. When reporting bugs, include:

- Server version (Spigot/Paper/Bukkit)
- Plugin version
- Java version
- QuickShop-Hikari version
- Steps to reproduce
- Relevant logs

## Suggesting Features

Open an issue with your idea. Explain why it'd be useful and how it might work.

## Pull Requests

Want to contribute code?

1. Fork and create a branch from `main`
2. Make your changes
3. Test on a local server
4. Update docs if needed
5. Make sure `mvn clean package` works
6. Submit your PR

## Development Setup

You'll need:
- JDK 16+
- Maven
- A test Minecraft server with QuickShop-Hikari
- Test Supabase project

```bash
git clone https://github.com/SwineFeather/QuickShop-Hikari-Supabase.git
cd QuickShop-Hikari-Supabase
mvn clean package
```

## Code Style

- 4 spaces for indentation
- Clear variable names
- Add comments for complex logic
- Keep methods focused and short

## Testing

Test your changes on a real server before submitting. Make sure:
- Plugin loads without errors
- Shops sync correctly
- Transactions record properly
- No console spam

## Questions?

Open an issue if you're unsure about anything.

