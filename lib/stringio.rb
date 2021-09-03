if RUBY_ENGINE == 'jruby'
  require 'stringio.jar'
  JRuby::Util.load_ext("org.jruby.ext.stringio.StringIOLibrary")
else
  require 'stringio.so'
end
