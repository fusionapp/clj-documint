(defproject documint "0.1.0-SNAPSHOT"
  :description "Document processing service"
  :url "http://github.com/fusionapp/clj-documint"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/tools.logging "0.4.0"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [org.danielsz/system "0.4.1"]
                 [org.xhtmlrenderer/flying-saucer-pdf-itext5 "9.1.9"]
                 [ring/ring-defaults "0.3.1"]
                 [ring/ring-jetty-adapter "1.6.3"]
                 [ring/ring-core "1.6.3"]
                 [danlentz/clj-uuid "0.1.7"]
                 [aleph "0.4.4"]
                 [liberator "0.15.1"]
                 [bidi "2.1.2"]
                 [compojure "1.6.0"]
                 [environ "1.1.0"]
                 [manifold "0.1.6"]
                 [org.apache.pdfbox/pdfbox "2.0.8"]
                 [org.bouncycastle/bcpkix-jdk15on "1.58"]
                 [org.bouncycastle/bcprov-jdk15on "1.58"]
                 [prismatic/schema "1.1.7"]
                 [com.twelvemonkeys.imageio/imageio-tiff "3.3.2"]
                 [com.twelvemonkeys.imageio/imageio-metadata "3.3.2"]
                 [com.levigo.jbig2/levigo-jbig2-imageio "2.0"]
                 [com.climate/claypoole "1.1.4"]
                 ]
  :plugins [[lein-environ "1.0.0"]
            [lein-cloverage "1.0.7-SNAPSHOT"]]
  :jvm-opts ["-Djava.awt.headless=true"]
  :main ^:skip-aot documint.core
  :target-path "target/%s"
  :profiles {:dev {:source-paths ["dev"]}
             :uberjar {:aot :all}
             :drone {:local-repo "m2"}})
