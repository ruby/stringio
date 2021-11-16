require "bundler/gem_tasks"
require "rake/testtask"

name = "stringio"

require 'rake/extensiontask'
extask = Rake::ExtensionTask.new(name) do |x|
  x.lib_dir << "/#{RUBY_VERSION}/#{x.platform}"
end
Rake::TestTask.new(:test) do |t|
  ENV["RUBYOPT"] = "-I" + [extask.lib_dir, "test/lib"].join(File::PATH_SEPARATOR)
  t.libs << extask.lib_dir
  t.libs << "test/lib"
  t.ruby_opts << "-rhelper"
  t.test_files = FileList["test/**/test_*.rb"]
end

task :default => :test
task :test => :compile
