export type DemoAccount = {
  id: string;
  name: string;
  email: string;
  color: "frost" | "ocean" | "aurora";
  monogram: string;
};

export type MailCategory = "Primary" | "Social" | "Promotions" | "Updates" | "Forums";

export type DemoMessage = {
  id: string;
  sender: string;
  address: string;
  to: string;
  time: string;
  body: string[];
};

export type DemoThread = {
  id: string;
  accountId: string;
  sender: string;
  address: string;
  subject: string;
  preview: string;
  time: string;
  date: string;
  category: MailCategory;
  unread: boolean;
  starred?: boolean;
  labels?: string[];
  messages: DemoMessage[];
};

export const demoAccounts: DemoAccount[] = [
  { id: "personal", name: "Personal", email: "taylor@sample.example", color: "frost", monogram: "P" },
  { id: "studio", name: "Studio", email: "hello@wildfern.example", color: "ocean", monogram: "S" },
  { id: "work", name: "Work", email: "inbox@northstar.example", color: "aurora", monogram: "W" },
];

export const demoThreads: DemoThread[] = [
  {
    id: "proposal",
    accountId: "work",
    sender: "Mira Chen",
    address: "mira@paperplane.example",
    subject: "Re: Northstar launch — final review",
    preview: "I’ve added the last round of comments to the launch brief. Could you take a look before our 3 pm review?",
    time: "10:42 AM",
    date: "Today, 10:42 AM",
    category: "Primary",
    unread: true,
    starred: true,
    labels: ["Projects", "Needs review"],
    messages: [
      {
        id: "proposal-1",
        sender: "Mira Chen",
        address: "mira@paperplane.example",
        to: "you",
        time: "10:42 AM",
        body: [
          "Hi there,",
          "I’ve added the last round of comments to the launch brief. The product screenshots and final timeline are both in place now.",
          "Could you take a look before our 3 pm review? If the summary reads well, we should be ready to share it with the team.",
          "Thanks,\nMira",
        ],
      },
      {
        id: "proposal-2",
        sender: "You",
        address: "inbox@northstar.example",
        to: "Mira Chen",
        time: "Yesterday, 4:18 PM",
        body: [
          "Thanks, Mira. I’ll review the final brief this morning and send any last edits before the afternoon check-in.",
          "Best,\nTaylor",
        ],
      },
    ],
  },
  {
    id: "invoice",
    accountId: "studio",
    sender: "Wildfern Supply",
    address: "billing@wildfern.example",
    subject: "Your order receipt — #WF-2048",
    preview: "Thanks for your order. Your receipt is ready, including the ceramic planters and delivery details.",
    time: "9:16 AM",
    date: "Today, 9:16 AM",
    category: "Updates",
    unread: true,
    labels: ["Receipts"],
    messages: [
      {
        id: "invoice-1",
        sender: "Wildfern Supply",
        address: "billing@wildfern.example",
        to: "you",
        time: "9:16 AM",
        body: [
          "Thank you for your order.",
          "Your order WF-2048 includes two small ceramic planters and one linen plant mat. The receipt total is $48.00 USD.",
          "We’ll send another note when the parcel is on its way.",
        ],
      },
    ],
  },
  {
    id: "appointment",
    accountId: "personal",
    sender: "Juniper Dental",
    address: "appointments@junipercare.example",
    subject: "A quick reminder for Thursday",
    preview: "Your appointment is booked for Thursday, November 12 at 2:30 pm. Reply if you need to make a change.",
    time: "Yesterday",
    date: "Yesterday, 6:30 PM",
    category: "Primary",
    unread: true,
    labels: ["Personal"],
    messages: [
      {
        id: "appointment-1",
        sender: "Juniper Dental",
        address: "appointments@junipercare.example",
        to: "you",
        time: "6:30 PM",
        body: [
          "Hello Taylor,",
          "This is a reminder that your appointment is booked for Thursday, November 12 at 2:30 pm.",
          "If you need to make a change, just reply to this message and our team will be happy to help.",
        ],
      },
    ],
  },
  {
    id: "reply",
    accountId: "work",
    sender: "Avery Brooks",
    address: "avery@northstar.example",
    subject: "Re: Updated budget estimate",
    preview: "This looks good to me. The revised numbers match what we discussed on Tuesday.",
    time: "Yesterday",
    date: "Yesterday, 3:05 PM",
    category: "Primary",
    unread: true,
    messages: [
      {
        id: "reply-1",
        sender: "Avery Brooks",
        address: "avery@northstar.example",
        to: "you",
        time: "3:05 PM",
        body: [
          "Hi Taylor,",
          "This looks good to me. The revised numbers match what we discussed on Tuesday.",
          "Please use this version for the planning meeting. Thanks for pulling it together.",
        ],
      },
      {
        id: "reply-2",
        sender: "You",
        address: "inbox@northstar.example",
        to: "Avery Brooks",
        time: "Tuesday, 1:12 PM",
        body: ["Hi Avery,\nI’ve updated the estimate with the new support line and moved the delivery window to Q1."],
      },
    ],
  },
  {
    id: "newsletter",
    accountId: "personal",
    sender: "Good Things Weekly",
    address: "letters@goodthings.example",
    subject: "Five small rituals for a slower week",
    preview: "A few ideas from our readers about making a little room for rest and focus.",
    time: "Tue",
    date: "Tuesday, 8:00 AM",
    category: "Promotions",
    unread: false,
    messages: [
      {
        id: "newsletter-1",
        sender: "Good Things Weekly",
        address: "letters@goodthings.example",
        to: "you",
        time: "8:00 AM",
        body: [
          "A little note for your week.",
          "Readers shared a few small rituals that help them pause between tasks: a walk around the block, a quiet cup of tea, and a short list for tomorrow.",
          "Hope one of them brings a little more breathing room to your week.",
        ],
      },
    ],
  },
  {
    id: "social",
    accountId: "studio",
    sender: "Moss & Mingle",
    address: "community@mossandmingle.example",
    subject: "Your friends shared new photos",
    preview: "There are a few new moments waiting in your community album.",
    time: "Tue",
    date: "Tuesday, 7:32 AM",
    category: "Social",
    unread: false,
    messages: [
      {
        id: "social-1",
        sender: "Moss & Mingle",
        address: "community@mossandmingle.example",
        to: "you",
        time: "7:32 AM",
        body: ["Your friends shared new photos in the community album. Open the album when you have a moment to catch up."],
      },
    ],
  },
  {
    id: "domain",
    accountId: "work",
    sender: "Mina Patel",
    address: "mina@paperplane.example",
    subject: "Paperplane domain renewal",
    preview: "The annual renewal is coming up on November 18. The current plan is listed below.",
    time: "Mon",
    date: "Monday, 11:14 AM",
    category: "Updates",
    unread: false,
    labels: ["Operations"],
    messages: [
      {
        id: "domain-1",
        sender: "Mina Patel",
        address: "mina@paperplane.example",
        to: "you",
        time: "11:14 AM",
        body: [
          "Hi,",
          "The Paperplane domain renewal is coming up on November 18. The annual plan remains $24.00 USD.",
          "Let me know if you'd like me to change anything before the renewal date.",
        ],
      },
    ],
  },
  {
    id: "forum",
    accountId: "personal",
    sender: "Kitchen Notes Community",
    address: "digest@kitchennotes.example",
    subject: "This week in the home-cooking forum",
    preview: "Readers are swapping cozy recipes and tips for making weekday dinners easier.",
    time: "Mon",
    date: "Monday, 8:15 AM",
    category: "Forums",
    unread: false,
    messages: [
      {
        id: "forum-1",
        sender: "Kitchen Notes Community",
        address: "digest@kitchennotes.example",
        to: "you",
        time: "8:15 AM",
        body: ["A weekly digest of new conversations from the home-cooking forum, including weeknight soups, simple breads, and meal planning ideas."],
      },
    ],
  },
];

export const dashboardSummary =
  "Across your three sample accounts, project planning is moving forward, a reply came in on the updated estimate, and a few messages may need a look today. Your inbox also includes a receipt, an upcoming appointment, and a renewal notice. Nothing here has been sent or changed — this is fictional demo content.";
