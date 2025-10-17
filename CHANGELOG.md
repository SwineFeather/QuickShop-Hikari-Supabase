# Changelog

## [1.0.0] - 2025

Initial release.

### Features
- Real-time shop sync to Supabase
- Automatic transaction recording
- Event-driven updates (no polling)
- Smart rate limiting to prevent API timeouts
- Batch processing for server performance
- Retry logic with exponential backoff
- Item display names for all vanilla items
- Support for enchantments, lore, custom model data

### Commands
- `/qss status` - Check sync status
- `/qss sync` - Force manual sync
- `/qss reload` - Reload config

### Database
- Optimized schema with proper indexing
- Shop statistics view
- Transaction history with timestamps

## Roadmap

Considering for future releases:
- Multi-server support
- Price history tracking
- Webhook notifications

