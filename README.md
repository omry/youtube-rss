* Copy conf.sample to conf and edit.
* Copy channels.sample to channels and edit.
* Currently the run method is using this bash command:
	while true; do ./YoutubeRSS.scala --delete-old --catch-up ;echo sleeping;sleep 15m; done
  Server mode is implemented but due to a memory leak in scala xml parsing (at least when I wrote this) its better to have the loop outside.
