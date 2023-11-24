require "bundler/gem_tasks"
require "rake/testtask"

name = "stringio"

case RUBY_ENGINE
when "jruby"
  require 'rake/javaextensiontask'
  extask = Rake::JavaExtensionTask.new("stringio") do |ext|
    ext.lib_dir << "/#{ext.platform}"
    ext.source_version = '1.8'
    ext.target_version = '1.8'
    ext.ext_dir = 'ext/java'
  end

  task :build => "#{extask.lib_dir}/#{extask.name}.jar"
when "ruby"
  require 'rake/extensiontask'
  extask = Rake::ExtensionTask.new(name) do |x|
    x.lib_dir << "/#{RUBY_VERSION}/#{x.platform}"
  end
else
  task :compile
end

Rake::TestTask.new(:test) do |t|
  if extask
    ENV["RUBYOPT"] = "-I" + [extask.lib_dir, "test/lib"].join(File::PATH_SEPARATOR)
    t.libs << extask.lib_dir
  else
    ENV["RUBYOPT"] = "-Itest/lib"
  end
  t.libs << "test/lib"
  t.ruby_opts << "-rhelper"
  t.test_files = FileList["test/**/test_*.rb"]
end

task :default => :test
task :test => :compile
