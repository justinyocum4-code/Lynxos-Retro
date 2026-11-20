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
window.SUPABASE_URL = "https://pyewacxllhmtizkylidv.supabase.co";
window.SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InB5ZXdhY3hsbGhtdGl6a3lsaWR2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTUyMDg1MzUsImV4cCI6MjExMDc4NDUzNX0.icoJa0aeRcNp1jnSoR9BzKxapYa4Ktc_YUZUyNPpyRc";
