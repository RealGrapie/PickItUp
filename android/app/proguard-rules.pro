# PebbleKit2 ships no consumer rules and talks to the Pebble app over binder/parcels: keep it whole.
-keep class io.rebble.pebblekit2.** { *; }
-dontwarn io.rebble.pebblekit2.**
