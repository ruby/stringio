require "bundler/gem_tasks"
require "rake/testtask"
require "rdoc/task"

name = "stringio"

helper = Bundler::GemHelper.instance

case RUBY_ENGINE
when "jruby"
  require 'rake/javaextensiontask'
  extask = Rake::JavaExtensionTask.new("stringio") do |ext|
    ext.lib_dir << "/#{ext.platform}"
    ext.release = '8'
    ext.ext_dir = 'ext/java'
  end
  libs = [extask.lib_dir]

  task :build => "#{extask.lib_dir}/#{extask.name}.jar"
when "ruby"
  require "ruby-core/extensiontask"
  libs = RubyCore::ExtensionTask.new(helper.gemspec).libs
  task :test => :compile
else
  task :compile
end

Rake::TestTask.new(:test) do |t|
  t.libs.push(*libs, "test/lib")
  ENV["RUBYOPT"] = "-I" + t.libs.join(File::PATH_SEPARATOR)
  t.ruby_opts << "-rhelper"
  t.test_files = FileList["test/**/test_*.rb"]
end

RDoc::Task.new do |rdoc|
  rdoc.rdoc_files.push("COPYING", "LICENSE.txt",
                       "NEWS.md", "README.md",
                       "docs/io.rb", "ext/stringio/stringio.c")
end

task :default => :test
task :test => :compile
