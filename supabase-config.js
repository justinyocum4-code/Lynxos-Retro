// Lynxos Retro — cloud sync settings.
// Fill these in after creating the "Lynxos Retro" project in Supabase:
//   Supabase dashboard -> Project Settings -> API:
//   - Project URL goes in SUPABASE_URL
//   - anon (public) key goes in SUPABASE_ANON_KEY
// NEVER put the service-role key here. The anon key is safe to ship
// only because Row Level Security is enabled on the game_library table
// and the roms storage bucket (see README.md).
// Until these are filled in, the app works fully offline with the
// games included in the app, and shows "Cloud sync is not set up yet".
window.SUPABASE_URL = "YOUR_SUPABASE_URL_HERE";
window.SUPABASE_ANON_KEY = "YOUR_SUPABASE_ANON_KEY_HERE";
