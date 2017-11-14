(set-env!
 :resource-paths #{"src"}
 :dependencies '[[org.clojure/clojure "1.8.0"]
                 [danielsz/boot-environ "0.0.5"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/tools.logging "0.4.0"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [org.danielsz/system "0.4.0"]
                 [org.xhtmlrenderer/flying-saucer-pdf-itext5 "9.1.6"]
                 [ring/ring-defaults "0.3.1"]
                 [ring/ring-jetty-adapter "1.6.2"]
                 [ring/ring-core "1.6.2"]
                 [danlentz/clj-uuid "0.1.7"]
                 [aleph "0.4.3"]
                 [liberator "0.15.1"]
                 [bidi "2.1.2"]
                 [compojure "1.6.0"]
                 [environ "1.1.0"]
                 [manifold "0.1.6"]
                 [org.apache.pdfbox/pdfbox "2.0.7"]
                 [org.bouncycastle/bcpkix-jdk15on "1.58"]
                 [org.bouncycastle/bcprov-jdk15on "1.58"]
                 [prismatic/schema "1.1.6"]
                 [com.twelvemonkeys.imageio/imageio-tiff "3.3.2"]
                 [com.twelvemonkeys.imageio/imageio-metadata "3.3.2"]
                 [com.levigo.jbig2/levigo-jbig2-imageio "2.0"]
                 [com.climate/claypoole "1.1.4"]
                 ])


(require
 '[system.repl :as repl :refer [start stop go reset]]
 '[documint.systems :refer [dev-system]]
 '[danielsz.boot-environ :refer [environ]]
 '[system.boot :refer [system run]])


(deftask dev
  "Run a restartable system in the Repl"
  []
  (comp
   (watch :verbose true)
   (system :sys #'dev-system :auto true :files ["clj$"] :regexes true)
   (repl :server true)))


(deftask dev-run
  "Run a dev system from the command line"
  []
  (comp
   (run :main-namespace "documint.core" :arguments [#'dev-system])
   (wait)))


(deftask build
  "Builds an uberjar of this project that can be run with java -jar"
  []
  (comp
   (aot :namespace '#{documint.core})
   (pom :project 'myproject
        :version "0.1.0-SNAPSHOT")
   (uber)
   (jar :main 'documint.core)))
