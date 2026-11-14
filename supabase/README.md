# IntraMap Supabase setup

1. Create a Supabase project.
2. Database > Extensions: enable `postgis` (schema `extensions`).
3. SQL Editor: run `migrations/0001_schema.sql`, then `migrations/0002_rls.sql`.
4. Storage: create private buckets `floor-plans`, `reference-images`, `path-recordings`; add policies so only building editors can write.
5. Authentication: enable Email provider; create your admin user.
6. Add to `local.properties` (never commit):

```
SUPABASE_URL=https://YOUR_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR_PUBLISHABLE_KEY
```

Never put the service_role key or database password in the app or repository.
