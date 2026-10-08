# GikkuNavigation

Java source prototype for a non-visible Kodular / MIT App Inventor GPS navigation extension.

**Status: Experimental — not yet compiled into a verified .aix or tested on Android.**

Features under development: Places API (New) text search, coordinate/name/place-ID destinations and ordered stops, Android LocationManager GPS tracking, and Routes API computeRoutes with traffic-aware driving directions.

See the Java source in `src/com/gikku/navigation/GikkuNavigation.java`.

## Build status
The workflow in `.github/workflows/build.yml` is a scaffold and does not yet produce a valid `.aix`. A compatible App Inventor extension compiler must be integrated and tested.

## Known limitations
- Place ID recognition in this prototype only covers `ChI...` prefixes.
- After location permission is granted, `StartLocation()` must be called again.
- No physical Android tests or live Google API tests have been performed.
- Never commit Google API keys; configure Places API (New), Routes API, and billing in Google Cloud.

## Testing
Set the API key in Kodular, start GPS, search for a location, set a destination (address, coordinates, or supported Place ID), manage stops, and call `CalculateRoute` after a GPS fix. Check `NavigationError` for failures.
