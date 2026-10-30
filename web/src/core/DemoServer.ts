// Port of android/core DemoServer.kt.
import { MediaServer } from './MediaServer'
import { LibrarySection, MediaItem, PlaybackSource, ServerKind, TrackSelection } from './Models'
import * as Time from './Time'

interface Ep { readonly s: number; readonly e: number; readonly title: string; readonly overview: string }
const ep = (s: number, e: number, title: string, overview: string): Ep => ({ s, e, title, overview })

/**
 * Fake library so the guide can be developed and demoed without a real server.
 * Everything is invented. The cast/crew/years are arranged so every automatic channel type shows up:
 * split genre (Comedy films / TV), a TV-only genre (Crime: too few films for a films channel), 3 marathons, 3 directors, 3 actors, Class of ’85.
 */
export class DemoMediaServer extends MediaServer {
  readonly kind: ServerKind = 'demo'
  readonly serverName = 'Demo library'

  async libraries(): Promise<LibrarySection[]> {
    return [
      { id: 'demo-movies', title: 'Movies', kind: 'movies', itemCount: 20 },
      { id: 'demo-tv', title: 'TV Shows', kind: 'shows', itemCount: 3 },
    ]
  }

  async libraryItems(sections: readonly LibrarySection[] | null): Promise<MediaItem[]> {
    if (sections == null) return DemoMediaServer.items.slice()
    const ids = sections.map(it => it.id)
    return DemoMediaServer.items.filter(it => ids.indexOf(it.libraryID ?? '') >= 0)
  }
  // trailer(item, session) is the base class default (null): the demo has no trailers, so breaks show the station card.

  /** A fixed ~10 min sample: `startsAtOffset = true` so nobody seeks it to a 0–140 min clock offset; the watch player loops it. */
  async playbackSource(item: MediaItem, startingAt: number, session: string, tracks?: TrackSelection): Promise<PlaybackSource> {
    return {
      url: 'https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8',
      startsAtOffset: true,
    }
  }

  artworkURL(path: string | null | undefined): string | null {
    return null
  }

  /** Fixed, hand-written library: 20 invented retro movies + 3 series × 8 episodes. Runtimes in seconds. */
  static readonly items: readonly MediaItem[] = makeItems()
}

function makeItems(): MediaItem[] {
  const loaded = Time.now()
  const movie = (n: number, title: string, year: number, mins: number, rating: string, genres: string[],
                 director: string, actors: string[], overview: string): MediaItem =>
    new MediaItem({
      id: `m${n}`, kind: 'movie', title, year, genres, runtime: mins * 60, overview, contentRating: rating,
      directors: [director], actors, libraryID: 'demo-movies',
      addedAt: loaded - n * 6 * 86_400,   // one movie every 6 days → ~10 "Just Added"
    })
  const series = (id: string, name: string, year: number, mins: number, genres: string[], eps: Ep[]): MediaItem[] =>
    eps.map(e => new MediaItem({
      id: `${id}-s${e.s}e${e.e}`, kind: 'episode', title: e.title, seriesTitle: name, seriesID: id,
      season: e.s, episode: e.e, year, genres, runtime: mins * 60, overview: e.overview, contentRating: 'TV-PG', libraryID: 'demo-tv',
    }))
  const vole = 'Marguerite Vole'; const calhoun = 'Dusty Calhoun'; const okonkwo = 'Len Okonkwo'; const marsh = 'Priya Marsh'
  const rex = 'Rex Tallow'; const dolores = 'Dolores Finch'; const kip = 'Kip Navarro'; const bea = 'Bea Saltonstall'
  const hal = 'Hal Pruett'; const wendell = 'Wendell Ash'; const nina = 'Nina Castellano'; const ozzie = 'Ozzie Blankenship'
  return [
    movie(1, 'Night of the Rental Returns', 1985, 92, 'R', ['Horror', 'Comedy'], vole, [rex, dolores],
      'A video-store night clerk discovers the overdue tapes are coming back on their own, and they are angry about the late fees.'),
    movie(2, 'Chrome Horizon', 1984, 118, 'PG', ['Sci-Fi'], vole, [kip, bea],
      'A test pilot flies a prototype past the edge of the atmosphere and finds a second, mirrored Earth waiting there.'),
    movie(3, 'The Last Payphone', 1996, 104, 'R', ['Drama'], okonkwo, [dolores, hal],
      'A night-shift dispatcher keeps answering the one payphone in town nobody else will pick up.'),
    movie(4, 'Mullet Force', 1985, 97, 'PG-13', ['Action', 'Comedy'], calhoun, [rex, kip],
      'Four hairdressers with military pasts are the only ones who can stop a hostile takeover of the state fair.'),
    movie(5, 'Suburban Meteor', 1991, 101, 'PG', ['Sci-Fi', 'Comedy'], vole, [rex, bea],
      "A meteorite lands in a cul-de-sac and the homeowners' association wants it moved by Friday."),
    movie(6, 'Blood on the Boardwalk', 1982, 88, 'R', ['Horror'], vole, [dolores, wendell],
      'Something under the pier is picking off carnival workers one summer night at a time.'),
    movie(7, 'Dial Tone', 1993, 110, 'PG-13', ['Drama'], marsh, [dolores, kip],
      "A phone-company lineman starts hearing a stranger's confessions on a crossed wire and can't stop listening."),
    movie(8, 'Rooftop Reckoning', 1990, 112, 'R', ['Action', 'Crime'], calhoun, [rex, hal],
      'A window washer trapped forty stories up becomes the only witness to a heist in progress.'),
    movie(9, 'The Beige Conspiracy', 1978, 126, 'PG', ['Drama'], okonkwo, [bea, hal],
      'A junior accountant notices every office in the building is painted the same color, and starts asking why.'),
    movie(10, 'Laser Prom', 1985, 95, 'PG', ['Sci-Fi', 'Comedy'], calhoun, [kip, dolores],
      "The prom committee's borrowed light show turns out to be alien technology with opinions about the playlist."),
    movie(11, 'Static', 1998, 99, 'R', ['Horror'], vole, [wendell, nina],
      'A late-night radio engineer realizes the dead air between stations has started to talk back.'),
    movie(12, 'Two Tickets to Tuesday', 1995, 93, 'PG', ['Comedy'], marsh, [dolores, rex],
      'Two strangers hold the same winning raffle ticket and have one week to decide who gets the cruise.'),
    movie(13, 'Iron Cul-de-Sac', 1988, 108, 'R', ['Action'], calhoun, [rex, nina],
      'A retired demolition expert defends his street from developers using everything in his garage.'),
    movie(14, 'Orbital Lullaby', 1997, 132, 'PG-13', ['Sci-Fi', 'Drama'], okonkwo, [kip, wendell],
      'The last crew member awake on a sleeper ship has to decide whether to wake the others for the view.'),
    movie(15, 'The Cassette Killer', 1985, 90, 'R', ['Horror'], vole, [dolores, kip],
      'Every mixtape left on the diner counter plays a song the listener will never finish.'),
    movie(16, 'Floodlight', 1979, 140, 'PG', ['Drama'], marsh, [nina, ozzie],
      "A small-town stadium's night-game lights become the center of a fight over who the town belongs to."),
    movie(17, 'Big Hair, Bigger Trouble', 1992, 85, 'PG-13', ['Comedy'], calhoun, [rex, dolores],
      "A salon owner's regional styling title is challenged by her own twin sister."),
    movie(18, 'Harbor Protocol', 1994, 121, 'R', ['Action', 'Drama', 'Crime'], calhoun, [kip, ozzie],
      "A harbor master with one night left before retirement finds a cargo manifest that doesn't add up."),
    movie(19, 'Moonbase Midnight', 1981, 103, 'PG-13', ['Sci-Fi', 'Horror'], vole, [ozzie, rex],
      'The night crew of a lunar mining base discovers their shift has been running for three years.'),
    movie(20, 'Reunion Weekend', 1999, 96, 'PG', ['Comedy', 'Drama'], okonkwo, [dolores, kip],
      'A class reunion at a motel with one working ice machine forces old friends to settle twenty years of accounts.'),
  ].concat(
    series('cds', 'Cul-de-Sac Confidential', 1988, 22, ['Comedy'], [
      ep(1, 1, 'The Welcome Casserole', 'The new neighbors get a casserole from every house on the street, and every cook wants their dish back first.'),
      ep(1, 2, 'Garage Sale Gambit', 'A joint garage sale turns competitive when one driveway starts offering free lemonade.'),
      ep(1, 3, 'Sprinkler Wars', 'Two lawns, one shared water line, and a watering schedule nobody agreed to.'),
      ep(1, 4, 'The Mailbox Incident', 'A misdelivered package reveals which neighbor has been ordering the same novelty item for years.'),
      ep(1, 5, 'Block Party Blues', 'The block party band breaks up during sound check.'),
      ep(1, 6, 'Hedge Fund', 'The street pools money for a hedge trimmer and immediately argues about custody.'),
      ep(1, 7, 'Carpool Confessions', 'A stalled carpool van becomes a confessional on the longest morning commute of the year.'),
      ep(1, 8, 'The Big Bake-Off', 'The annual bake-off judge turns out to have no sense of taste.'),
    ]),
    series('smh', 'Strip Mall Heroes', 1994, 22, ['Comedy'], [
      ep(1, 1, 'Grand Opening', "The mall's newest tenant opens on the same day as the parking-lot repaving."),
      ep(1, 2, 'The Food Court Feud', 'Two neighboring pretzel stands go to war over a single shared napkin dispenser.'),
      ep(1, 3, 'Arcade Fire Drill', "An unscheduled fire drill traps the arcade's high-score holder mid-run."),
      ep(1, 4, 'Mannequin Overboard', 'A display mannequin goes missing and every store blames the one next door.'),
      ep(2, 1, 'Return Policy', 'A customer tries to return an item from a store that closed in 1987.'),
      ep(2, 2, 'Escalator Etiquette', "The mall's only escalator breaks and the tenants draft a stair treaty."),
      ep(2, 3, 'The Pretzel Protocol', 'A health inspection sends the food court into a cover-up involving one very large pretzel.'),
      ep(2, 4, 'Closing Time', 'The mall announces new hours, and nobody can agree on which clock is right.'),
    ]),
    series('p9', 'Precinct 9 at Nine', 1991, 44, ['Drama', 'Crime'], [
      ep(1, 1, 'Night Shift', "A rookie's first night shift ends with a confession nobody asked for."),
      ep(1, 2, 'Cold Coffee', 'A diner robbery goes wrong when the detective on break turns out to be the only customer.'),
      ep(1, 3, 'The Pawn Shop Job', 'A pawned trumpet leads the squad to a string of burglaries across the precinct.'),
      ep(1, 4, 'Internal Affairs', "An internal investigation puts the night shift's favorite sergeant under the lamp."),
      ep(1, 5, 'Dead Drop', 'A briefcase left on the number 9 bus has everyone wondering who it was meant for.'),
      ep(1, 6, 'Witness', 'The only witness to a hit-and-run is a parking attendant who swears he saw nothing.'),
      ep(1, 7, 'The Long Stakeout', 'Two detectives spend eleven hours in a parked car waiting for a light to turn on.'),
      ep(1, 8, 'Badge Number', "A retired officer's badge number turns up on a fresh arrest report."),
    ]),
  )
}
