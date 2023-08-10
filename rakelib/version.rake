class << (helper = Bundler::GemHelper.instance)
  C_SOURCE_PATH = "ext/stringio/stringio.c"
  JAVA_SOURCE_PATH = "ext/java/org/jruby/ext/stringio/StringIO.java"
  def update_source_version
    c_source = File.read(C_SOURCE_PATH)
    c_source.sub!(/^#define\s+STRINGIO_VERSION\s+\K".*"/) {version.to_s.dump}
    File.write(C_SOURCE_PATH, c_source)

    java_source = File.read(JAVA_SOURCE_PATH)
    java_source.sub!(/version = RubyString\.newString\(runtime, \K".*"/) {version.to_s.dump}
    File.write(JAVA_SOURCE_PATH, java_source)
  end

  def commit_bump
    sh([*%w[git commit -m], "Development of #{gemspec.version} started.",
        SOURCE_PATH])
  end

  def version=(v)
    unless v == version
      unless already_tagged?
        ensure_news("Previous", version)
        abort "Previous version #{version} is not tagged yet"
      end
    end
    gemspec.version = v
    update_source_version
    commit_bump
  end

  def tag_version
    ensure_news("New", version)
    super
  end

  def ensure_news(that, version)
    news = File.read(File.join(__dir__, "../NEWS.md"))
    unless /^## +#{Regexp.quote(version.to_s)} -/ =~ news
      abort "#{that} version #{version} is not mentioned in NEWS.md"
    end
  end
end

major, minor, teeny = helper.gemspec.version.segments

desc "Bump teeny version"
task "bump:teeny" do
  helper.version = Gem::Version.new("#{major}.#{minor}.#{teeny+1}")
end

desc "Bump minor version"
task "bump:minor" do
  helper.version = Gem::Version.new("#{major}.#{minor+1}.0")
end

desc "Bump major version"
task "bump:major" do
  helper.version = Gem::Version.new("#{major+1}.0.0")
end

desc "Bump teeny version"
task "bump" => "bump:teeny"

task "tag" do
  helper.tag_version
end
