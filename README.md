sbt-rats provides a plugin that enables the [Rats! parser generator](https://cs.nyu.edu/rgrimm/xtc/rats.html) to be used in Scala projects.

The plugin enables you to use the [Rats! parser generator](https://cs.nyu.edu/rgrimm/xtc/rats.html) with Scala projects that are built using the [Scala build tool sbt](https://www.scala-sbt.org). The parser can be specified directly using a Rats! specification or using a simplified syntactic notation. The syntactic notation can also be translated into a Scala implementation of abstract syntax trees and a pretty printer for those trees. Pretty-printing support is provided by the [Kiama language processing library](https://github.com/inkytonik/kiama).

sbt-rats is a project of the [Programming Languages Research Group](https://wiki.mq.edu.au/display/plrg/Welcome) in the [Department of Computing](http://www.comp.mq.edu.au/) at [Macquarie University](http://www.mq.edu.au) and is led by [Tony Sloane](https://github.com/inkytonik) (inkytonik).

## Usage

For information on usage, see the [documentation](https://github.com/inkytonik/sbt-rats/blob/master/wiki/usage.md).

For an overview of sbt-rats, see our [overview paper on sbt-rats from the 2016 Scala Symposium](https://dl.acm.org/authorize?N27522) and the [slides from the associated presentation](https://speakerdeck.com/inkytonik/the-sbt-rats-parser-generator-plugin-for-scala).

There is also a sbt template showing a [simple usage of the plugin with Kiama](https://github.com/inkytonik/kiama-rats.g8).

## News

* February 15, 2021: [version 2.9.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.9.0.markdown) released on [bintray](https://bintray.com/inkytonik/sbt-plugins/sbt-rats/view)
* September 21, 2020: [version 2.8.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.8.0.markdown) released on [bintray](https://bintray.com/inkytonik/sbt-plugins/sbt-rats/view)
* April 20, 2020: [version 2.7.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.7.0.markdown) released on [bintray](https://bintray.com/inkytonik/sbt-plugins/sbt-rats/view)
* March 22, 2019: [version 2.6.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.6.0.markdown) released on [bintray](https://bintray.com/inkytonik/sbt-plugins/sbt-rats/view)
* September 28, 2018: [version 2.5.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.5.0.markdown) released on [bintray](https://bintray.com/inkytonik/sbt-plugins/sbt-rats/view)
* August 3, 2016: [version 2.4.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.4.0.markdown) released on [bintray](https://bintray.com/inkytonik/sbt-plugins/sbt-rats/view)
* April 7, 2016: [version 2.3.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.3.0.markdown) released on [bintray](https://bintray.com/inkytonik/sbt-plugins/sbt-rats/view)
* April 21, 2015: [version 2.2.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.2.0.markdown) released on [bintray](https://bintray.com/inkytonik/sbt-plugins/sbt-rats/view)
* April 21, 2015: project moved to [BitBucket](https://github.com/inkytonik/sbt-rats)
* October 26, 2012: [version 2.1.0](https://github.com/inkytonik/sbt-rats/blob/master/notes/2.1.0.markdown) released
* August 9, 2012: Slides for a ScalaSyd talk on sbt-rats [posted](https://speakerdeck.com/inkytonik/sbt-rats-packrat-parser-generation-for-scala).

## License

sbt-rats is released under the New BSD License.  See LICENSE for details.
