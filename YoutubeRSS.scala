#!/bin/sh
exec /home/omry/bin/scala -savecompiled -classpath "lib/*" $0 $@
!#

import scala.io.Source
import scala.xml._
import scala.actors.Actor
import sys.process._
import java.io._
import com.sun.syndication.feed._
import com.sun.syndication.feed.synd._
import com.sun.syndication.io._
import java.text._

object Conf
{
	val YOUTUBE_CHANNEL_URL = "http://gdata.youtube.com/feeds/api/users/%s/uploads"
	val YOUTUBE_PROFILE_URL = "http://gdata.youtube.com/feeds/api/users/%s"
	val YOUTUBE_WATCH_URL = "http://www.youtube.com/watch?v=%s"
	val BIN_DIR = "/home/omry/youtube-rss/bin"
	val DOWNLOAD_DIR = "/home/omry/youtube-rss/download"
	val RSS_WEB_DIR="/home/omry/www/youtube-rss.firefang.net"
	val RSS_BASE_URL="http://youtube-rss.firefang.net"
	val DELETE_OLDER="14"
	val CHECK_INTERVAL_MIN="15"
	val NEVER_DOWNLOAD="false"
	val VIDEO_DIR=""
	val RATE_LIMIT=""

	{	
		val props = new java.util.Properties
		val fin = new java.io.FileInputStream("conf")
		try{
			props.load(fin)
		}finally{
			fin.close()
		}
		getClass.getDeclaredFields.foreach(field => {
			field.setAccessible(true)
			val name = field.getName
			val value = field.get(this).asInstanceOf[String]
			field.set(this, props.getProperty(name, value))
			//println(name + "->" + field.get(this))
		})
	}

}

val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

object YoutubeRSS 
{
	val encoder = new Encoder
	val downloader = new Downloader(encoder)

	var simulate = false
	var skip_encoding =  false
	var catch_up = false
	var delete_old = false
	var regen_feeds = false
	var channel : String = null
	var server = false
	var debug = false
	var refresh_metafiles = false

    def main(args: Array[String]) 
	{
		println("Starting YoutubeRSS")
		try
		{
			val r_channel = """--channel=(.*)""".r
			var exit=false
			for(arg <- args){
				arg match
				{
					case "--simulate"|"-c" => simulate = true
					case "--catch-up" => catch_up = true				
					case "--delete-old" => delete_old = true
					case "--regenerate-feeds" => regen_feeds = true
					case "--skip-encoding" => skip_encoding = true
					case "--server" => server = true
					case "--debug" => debug = true
					case "--refresh-metafiles" => refresh_metafiles = true
					case "--generate-index" =>
					{
						generateIndex
						exit = true
					}
					case "--update-video-dir" =>
					{
						updateVideoDir
						exit = true
					}
					case r_channel(channe1) => 
						channel = channe1
						println("Processing only channel %s".format(channe1))
					case x  => println("Unknown parameter " + x)
				}
			}

			if (regen_feeds) regenerateFeeds(simulate)
			if (exit)sys.exit(0)
			
			if (Conf.NEVER_DOWNLOAD.toBoolean) sys.exit(0)
			do
			{
				if (delete_old) deleteOldFiles(simulate)
				if(catch_up) encodeMissing(simulate)
				if (channel == null)
					Source.fromFile("channels").getLines.foreach(x => process(x trim, simulate) )
				else
					process(channel, simulate)
				if (server) 
				{
					println("Will sleep for %d minutes before next iteration".format(Conf.CHECK_INTERVAL_MIN.toInt))
					Thread.sleep(Conf.CHECK_INTERVAL_MIN.toInt * 60 * 1000)
				}
			}
			while(server)
		}
		finally
		{
			downloader ! Stop
		}
	}

	def process(channel : String, simulate : Boolean)
	{
		updateFeed(channel, simulate)
		val url = Conf.YOUTUBE_CHANNEL_URL.format(channel)
		println("Processing channel %s (%s)".format(channel, url))
		val rss = Source.fromURL(url).mkString
		val xml = XML.loadString(rss)
		val entries = xml \ "entry";
		entries foreach(entry => {
			downloader ! new Download(entry, channel, simulate, skip_encoding, Conf.RATE_LIMIT,refresh_metafiles, debug)

		})
	}

	def updateVideoDir
	{
		new File(Conf.VIDEO_DIR).listFiles.filter(_.isDirectory).foreach(f=>{
			f.listFiles.foreach(f=>{
				if (!f.getCanonicalFile.exists){
					println("stale symlink " + f)
					f.delete
				}
			})
		})
		new File(Conf.RSS_WEB_DIR).listFiles.filter(_.isDirectory).foreach(f=>{
			val channel = f.getName
			new File(Conf.RSS_WEB_DIR,channel).listFiles.filter(f=>f.isFile && f.getName.endsWith(".mp4")).foreach(f=>{
				val fname = f.getName
				val meta = new File(f + ".meta")
				if (meta.exists){
					val xml = Source.fromFile(new File(meta)).mkString
					val e = XML.loadString(xml)
					val author = (e \ "author" \ "name") text
					val title = (e \ "title").text
					new File(Conf.VIDEO_DIR,author).mkdir()
					val norma_title = title.replace('!','_').replace('`','_').replace('/','_')
					val dst = "%s/%s/%s.mp4".format(Conf.VIDEO_DIR,author,norma_title);
					if (!(new File(dst).exists)){
						println("creating symlink : %s\t-> %s".format(f.toString,dst));
						List("ln","-sf",f.toString,dst)!
					}

				}
			})
		})
	}
	
	def generateIndex()
	{
		println("Genereting index.html")
		val b = new StringBuilder
		b append """<html>
<head>
	<title>Youtube2RSS index</title>
</head>
<body>
<a href='index.opml'>OPML Index</a><br/><br/>
"""
		new File(Conf.RSS_WEB_DIR).listFiles.filter(d => d.isDirectory && new File(d, "feed.xml").exists).
			foreach(f =>
			{
				val channel = f.getName
				b append "<a href='%s/feed.xml'>%s</a><br/>\n".format(channel, getChannelTitle(channel))
			}
		)
		b append "</body></html>"
		writeFile(Conf.RSS_WEB_DIR + "/index.html", b toString)

		println("Genereting index.opml")
		b clear()
		b append """<?xml version="1.0" encoding="ISO-8859-1"?>
<opml version="2.0">
	<head>
		<title>YoutubeRSS index</title>
	</head>
<body>
"""
		new File(Conf.RSS_WEB_DIR).listFiles.filter(d => d.isDirectory && new File(d, "feed.xml").exists).
			foreach(f =>
			{
				val channel = f.getName
				val url = "%s/%s/feed.xml".format(Conf.RSS_BASE_URL,channel)
				b append "<outline text='%s' title='%s' type='rss' version='RSS2' xmlUrl='%s'/>\n".format(channel,channel, url)
			}
		)
		b append "</body></opml>"
		writeFile(Conf.RSS_WEB_DIR + "/index.opml", b toString)

	}

	def regenerateFeeds(simulate : Boolean)
	{
		new File(Conf.RSS_WEB_DIR).listFiles.
			filter(_.isDirectory()).
			foreach(file => updateFeed(dirChannel(file),simulate))
	}

	def encodeMissing(simulate : Boolean = false)
	{
		println("Encoding missing videos")
        new File(Conf.DOWNLOAD_DIR).listFiles.
            filter(_.isDirectory()).
            foreach(file => 
			{
				val channel = dirChannel(file)
				new File(Conf.DOWNLOAD_DIR, channel).listFiles.
					map(f => new File(f.getName)).
					filter(f => f.isFile() && (
							f.getName.endsWith(".mp4") || 
							f.getName.endsWith(".flv") ||
							f.getName.endsWith(".video")) && !new File(Conf.RSS_WEB_DIR + "/" + channel, f.getName).exists).
					foreach(vfile =>
					{
						
						println("Encoding missing : %s/%s".format(channel, vfile))
						val input = new File(Conf.DOWNLOAD_DIR + "/" + channel, vfile getName)
						val output = new File(Conf.RSS_WEB_DIR + "/" + channel, vfile getName)
						encoder ! Encode(channel, input, output, simulate)

					})
			})

	}

	def dirChannel(f : String) = 
	{
		val matcher = """.*/(.*)""".r
		val matcher(chan) = f
		chan
	}

	def deleteOldFiles(simulate : Boolean)
	{
		val updateChannels = new scala.collection.mutable.HashSet[String]
		List(Conf.DOWNLOAD_DIR,Conf.RSS_WEB_DIR).foreach(d=>
		{
			new File(d).listFiles.filter(_.isDirectory).foreach(d1=>
			{
				new File(d1).listFiles.filter(f=>{
						f.isFile && 
						f.lastModified < System.currentTimeMillis() - (Conf.DELETE_OLDER.toInt * 24 * 60 * 60 * (1000 toLong))
					}).foreach(f=>{
						println("Deleting old file : " + f)
						val ff = new File(f)
						ff.delete
						val p = ff.getParent 
						val matcher = """.*/(.*)""".r
						val matcher(channel) = p
						updateChannels.add(channel)
				})
			})
		})
		updateChannels foreach(updateFeed(_,simulate))
	}
}

def getChannelTitle(channel : String) =
{
	val rss = Source.fromURL(Conf.YOUTUBE_PROFILE_URL.format(channel)).mkString
	val xml = XML.loadString(rss)
	var title =  ((xml \ "title") text)
	if(title startsWith "YouTube user: ") title = title.substring("YouTube user: " length)
	title
}

def updateFeed(channel : String, simulate : Boolean)
{
	val title =  getChannelTitle(channel)
	println("Updating feed for %s (%s)".format(title, channel))
	val feed = new SyndFeedImpl();
	feed.setEncoding("UTF-8");
	feed.setFeedType("rss_2.0");
	feed.setTitle(title);
	feed.setLink(Conf.RSS_BASE_URL+"/"+channel);
	feed.setDescription(title);
	var entries = new java.util.ArrayList[SyndEntryImpl]();
	feed.setEntries(entries);

	var dir = Conf.RSS_WEB_DIR + "/" + channel
	val formatter = new java.text.SimpleDateFormat("dd MMM yyyy HH:mm:ss Z")
	val dirFile = new File(dir)
	if (!(dirFile exists)) 
	{
		println(dirFile + " missing");
		return
	}

	dirFile.listFiles.
		sortBy(_.lastModified).reverse.
		filter(_.getName().endsWith(".mp4")).
		map(file => file.substring(dir.length + 1)).
		foreach(file => 
		{
			val f = new File(dir,file)
			val metaFile = new File(f.toString + ".meta")
           	val entry = new SyndEntryImpl()
			if (metaFile exists)
			{
				val xml = Source.fromFile(metaFile).mkString
				if (xml.length == 0)
				{
					println("Empty file %s, removing and skipping".format(metaFile))
					new File(metaFile).delete
				}
				else
				{
					try
					{
						val e = XML.loadString(xml)
						val pubdate = e \ "published"
						val pubtime = df.parse(pubdate.text).getTime
						val author = (e \ "author" \ "name") text
						val title = (e \ "title").text
						val desc = (e \ "content").text
						entry setAuthor(author)
						entry setTitle(title)
						val sdesc = new SyndContentImpl
						sdesc setType("text")
						sdesc setValue(desc)
						entry setDescription(sdesc)
						val url = "%s/%s/%s".format(Conf.RSS_BASE_URL,channel,file)
						entry.setLink(url);
						entry.setPublishedDate(new java.util.Date(f lastModified))
						val enclosure = new SyndEnclosureImpl();
						enclosure.setUrl(url);
						enclosure.setType("video/mp4");
						enclosure.setLength(f.length())
						val enc = new java.util.ArrayList[SyndEnclosure]();
						enc.add(enclosure);
						entry.setEnclosures(enc);
						entries.add(entry);
					}
					catch
					{
						case e: Exception =>
							println("Error parsing %s : %s".format(metaFile, e getMessage));
					}
				}

			}
			else
			{
				println("Missing meta file for " + f)
/*
				val ii = file.indexOf('-')
				if (ii != -1)
				{
					val author = file.substring(0, ii)
					var title = file.substring(ii + 1)
					val xx = title.lastIndexOf(".mp4")
					if (xx != -1)
						title = title.substring(0, xx)
					entry.setTitle(title.replace('_', ' '))
					entry.setAuthor(author.replace('_', ' '))
				}
*/
			}
		})

		val feedfile= "%s/%s/feed.xml".format(Conf.RSS_WEB_DIR, channel)
		val output = new SyndFeedOutput()
		if (!simulate)
			writeFile(feedfile, output.outputString(feed))

}

def writeFile(file : String, list : List[String]){
	writeFile(file, list.foldRight("")((a,b) => a + "\n" + b))
}
def writeFile(file: String, text : String) : Unit = {
	val fw = new FileWriter(file)
		try{ fw.write(text) }
	finally{ fw.close }
}


case class Stop()
case class Sleep(ms : Int)
case class Download(entry : NodeSeq, channel : String, simulate : Boolean, skip_encoding : Boolean, rate_limit : String, refresh_metafiles : Boolean = false, debug : Boolean = false)
case class Encode(channel : String, input : String, output : String, simulate : Boolean)


class Downloader(encoder : Encoder)  extends Actor
{
	start

	def act =
		loop
		{
			react
			{
                case Download(entry, channel,simulate,skip_encoding,rate_limit,refresh_metafiles,debug) =>
				val link = (entry \ "link")(0) \ "@href"
				try
				{
					val text = (entry1:NodeSeq,key1:String) => if ((entry1 \ key1) != null) (entry1 \ key1).text else ""
					val pubdate = entry \ "published"
					val pubtime = df.parse(pubdate.text).getTime
					val author = (entry \ "author" \ "name") text
					val title = text(entry,"title")
					val desc = text(entry,"content")
					var result : String  = null
					var url = new java.net.URL(link.toString) 
					var query = url getQuery()
					var id = ""
					var pathR = """/feeds/api/videos/([0-9a-zA-Z-_]+)""".r
					var downloadUrl = ""
					if (query != null)
					{
						val params = query.split("&").map (line => {val x = line split "=";(x(0),x(1))}).toMap
						id = params("v")
						downloadUrl = link.toString
					}
					else
					{
						var path = url getPath()
						var pathR(id1) = path
						id = id1
						downloadUrl = Conf.YOUTUBE_WATCH_URL.format(id)
					}

					if (debug)
						println("video url : %s".format(downloadUrl))



					var skipOld = System.currentTimeMillis() - pubtime > (Conf.DELETE_OLDER.toInt * 24 * 60 * 60 * (1000 toLong))

					if (skipOld)
					{
						var ago = (System.currentTimeMillis() - pubtime) / (24 * 60 * 60 * (1000 toLong))
						if (debug)
							println("Skipping old : %s (id=%s) - pubtime = %s is %d days ago".format(title, id, pubdate.text, ago))
						continue
					}
					
					if (refresh_metafiles)
					{
						val fname = List(Conf.BIN_DIR + "/youtube-dl", "--cookies", "cookies.txt", "--get-filename","-o",Conf.DOWNLOAD_DIR+"/"+channel+"/%(id)s.%(ext)s",downloadUrl)!!

						result = fname trim()
						println("Refreshing metafile %s.meta".format(result))
						writeFile(result + ".meta", entry toString)
					}

					val exists = List("mp4","video","flv").map("%s/%s/%s.%s".format(Conf.DOWNLOAD_DIR,channel,id,_)).foldLeft(false)(_ || new java.io.File(_).exists)
					if (!exists)
					{
						val fname = List(Conf.BIN_DIR + "/youtube-dl", "--cookies", "cookies.txt", "--get-filename","-o",Conf.DOWNLOAD_DIR+"/"+channel+"/%(id)s.%(ext)s",downloadUrl)!!

						result = fname trim

						println("Downloading %s : %s into %s".format(author,title,result))
						if (!simulate) 
						{
							var args = List(Conf.BIN_DIR + "/youtube-dl",  "--cookies", "cookies.txt","--quiet","--max-quality=22","--no-progress","-o",Conf.DOWNLOAD_DIR+"/"+channel+"/%(id)s.%(ext)s",downloadUrl)
							if (rate_limit != "")
							{
								args = args ::: List("--rate-limit", rate_limit)
							}

							args!!

							writeFile(result + ".meta", entry toString)
						}

						println("Downloaded " +result)
						val FileName = String.format("""%s(.*)\..*""",Conf.DOWNLOAD_DIR).r
						val FileName(basename) = result.trim
						val output = Conf.RSS_WEB_DIR + "/" + basename + ".mp4"

						if (!skip_encoding)
							encoder ! Encode(channel, result, output, simulate)
						else
							println("Skipping encoding of " + output)	
	
					}
					else
					{
						if(debug)
							println("File for %s is already exists (%s)".format(id, title));
					}


				}
				catch
				{
					case e: Exception =>
						println("Error processing %s".format(link.toString))
						e.printStackTrace

				}
                case Stop => 
					encoder ! Stop
					exit
                case Sleep(ms) =>
                    println(Thread.currentThread().getName() + " sleeping")
                    java.lang.Thread.sleep(ms)
                case _ => println("Unknown message")
			}
		}
}

class Encoder extends Actor
{
    start
    def act =
        loop
        {
            react
            {
                case Encode(channel, input, output, simulate) =>
                    var destFile = new java.io.File(output)
                    if (!destFile.exists())
                    {
                        destFile.getParentFile().mkdirs()
                        println("Transcoding %s => %s".format(input, output))

                        if (!simulate)
                        {
							val logger = ProcessLogger((o: String)=>{},(e: String)=>{})

                            List(Conf.BIN_DIR+"/HandBrakeCLI","-Z","iPad","-i",input,"-o",output)!!logger

                            List("touch","-c","-r",input, output)!
                        }

						"cp -a %s %s".format(input + ".meta", output + ".meta")!;
                        updateFeed(channel, simulate)
                    }

                case Stop => exit
                case _ => println("Unknown message")
            }
        }
}


implicit def file2str(f:File) : String = f.toString

YoutubeRSS.main(args)
