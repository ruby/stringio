class << (helper = Bundler::GemHelper.instance)
  SOURCE_PATH = "lib/stringio/version.rb"
  def update_source_version
    path = SOURCE_PATH
    File.open(path, "r+b") do |f|
      d = f.read
      if d.sub!(/^\s+VERSION\s+=\s+\K".*"/) {version.to_s.dump}
        f.rewind
        f.truncate(0)
        f.print(d)
      end
    end
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
